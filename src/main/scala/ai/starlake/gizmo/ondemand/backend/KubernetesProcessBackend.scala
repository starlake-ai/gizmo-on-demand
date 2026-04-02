package ai.starlake.gizmo.ondemand.backend

import ai.starlake.gizmo.ondemand.config.KubernetesConfig
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientBuilder, Watch, Watcher}
import io.fabric8.kubernetes.client.Watcher.Action

import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*

/** Backend that creates Kubernetes Pods + Services for each proxy instance */
class KubernetesProcessBackend(config: KubernetesConfig) extends ProcessBackend with LazyLogging:

  private val client: KubernetesClient = new KubernetesClientBuilder().build()
  private val watches = TrieMap.empty[String, Watch]
  private val allocatedPorts = java.util.Collections.newSetFromMap(
    new java.util.concurrent.ConcurrentHashMap[Int, java.lang.Boolean]()
  ).asScala
  private val instanceExternalPorts = TrieMap.empty[String, Int]
  private val managedByLabel = "managed-by"
  private val managedByValue = "gizmo-process-manager"

  /** Sanitize name to be RFC 1123 compliant (K8s metadata.name) */
  private def sanitizeName(name: String): String =
    name.toLowerCase.replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").stripPrefix("-").stripSuffix("-").take(63)

  private def podName(name: String): String = s"gizmo-proxy-${sanitizeName(name)}"
  private def serviceName(name: String): String = s"gizmo-proxy-${sanitizeName(name)}"

  private def buildLabels(name: String): java.util.Map[String, String] =
    val labels = new java.util.HashMap[String, String]()
    labels.put(managedByLabel, managedByValue)
    labels.put("gizmo-instance", name)
    config.labels.foreach { case (k, v) => labels.put(k, v) }
    labels

  private def allocateExternalPort(): Option[Int] =
    config.nginxConfigMapName.flatMap { _ =>
      allocatedPorts.synchronized {
        (config.externalPortRangeStart to config.externalPortRangeEnd)
          .find(p => !allocatedPorts.contains(p))
          .map { port => allocatedPorts.add(port); port }
      }
    }

  private def releaseExternalPort(port: Int): Unit = allocatedPorts.remove(port)

  private def patchNginxConfigMap(externalPort: Int, sName: String): Unit =
    for
      cmName <- config.nginxConfigMapName
      cmNs = config.nginxConfigMapNamespace.getOrElse(config.namespace)
    do
      try
        val entry = s"${config.namespace}/$sName:${config.proxyPort}"
        val op: java.util.function.UnaryOperator[ConfigMap] = c => { c.getData.put(externalPort.toString, entry); c }
        client.configMaps().inNamespace(cmNs).withName(cmName).edit(op)
        logger.info(s"Patched nginx ConfigMap $cmNs/$cmName: $externalPort -> $entry")
      catch case e: Exception =>
        logger.error(s"Failed to patch nginx ConfigMap for port $externalPort", e)

  private def removeNginxConfigMapEntry(externalPort: Int): Unit =
    for
      cmName <- config.nginxConfigMapName
      cmNs = config.nginxConfigMapNamespace.getOrElse(config.namespace)
    do
      try
        val op: java.util.function.UnaryOperator[ConfigMap] = c => { c.getData.remove(externalPort.toString); c }
        client.configMaps().inNamespace(cmNs).withName(cmName).edit(op)
        logger.info(s"Removed nginx ConfigMap entry for port $externalPort")
      catch case e: Exception =>
        logger.error(s"Failed to remove nginx ConfigMap entry for port $externalPort", e)

  /** Remove nginx ConfigMap entries that point to services which no longer exist */
  private def cleanupOrphanConfigMapEntries(ns: String): Unit =
    for
      cmName <- config.nginxConfigMapName
      cmNs = config.nginxConfigMapNamespace.getOrElse(ns)
    do
      try
        val cm = client.configMaps().inNamespace(cmNs).withName(cmName).get()
        if cm != null && cm.getData != null then
          val entries = cm.getData.asScala.toMap
          entries.foreach { case (port, target) =>
            // target format: "namespace/serviceName:port"
            val svcName = target.split("/").lastOption.flatMap(_.split(":").headOption).getOrElse("")
            val svc = client.services().inNamespace(ns).withName(svcName).get()
            if svc == null then
              logger.info(s"Removing orphan nginx ConfigMap entry: port $port -> $target (service $svcName not found)")
              val op: java.util.function.UnaryOperator[ConfigMap] = c => { c.getData.remove(port); c }
              client.configMaps().inNamespace(cmNs).withName(cmName).edit(op)
              port.toIntOption.foreach(releaseExternalPort)
          }
      catch case e: Exception =>
        logger.warn(s"Failed to cleanup orphan ConfigMap entries: ${e.getMessage}")

  private def buildPod(
      pName: String,
      ns: String,
      labels: java.util.Map[String, String],
      envVars: Map[String, String],
      podProxyPort: Int,
      podBackendPort: Int
  ): Pod =
    // Build environment variables
    val containerEnvVars = new java.util.ArrayList[EnvVar]()
    envVars.foreach { case (k, v) =>
      val ev = new EnvVar()
      ev.setName(k)
      ev.setValue(v)
      containerEnvVars.add(ev)
    }
    Seq(
      ("PROXY_PORT", podProxyPort.toString),
      ("PROXY_HOST", "0.0.0.0"),
      ("GIZMO_SERVER_HOST", "127.0.0.1"),
      ("GIZMO_SERVER_PORT", podBackendPort.toString),
      // Disable the backend's plaintext health server to avoid port conflict with the proxy.
      // K8s probes use tcpSocket on the proxy port instead.
      ("HEALTH_PORT", "0")
    ).foreach { case (k, v) =>
      val ev = new EnvVar()
      ev.setName(k)
      ev.setValue(v)
      containerEnvVars.add(ev)
    }

    // Container ports
    val proxyContainerPort = new ContainerPort()
    proxyContainerPort.setContainerPort(podProxyPort)
    proxyContainerPort.setName("proxy")
    proxyContainerPort.setProtocol("TCP")

    val backendContainerPort = new ContainerPort()
    backendContainerPort.setContainerPort(podBackendPort)
    backendContainerPort.setName("backend")
    backendContainerPort.setProtocol("TCP")

    // Container — run ProxyServer directly, not the Process Manager
    val container = new Container()
    container.setName("gizmo-proxy")
    container.setImage(config.imageName)
    container.setImagePullPolicy(config.imagePullPolicy)
    container.setCommand(java.util.List.of(
      "/opt/gizmosql/scripts/docker-start-proxy.sh"
    ))
    container.setEnv(containerEnvVars)
    container.setPorts(java.util.List.of(proxyContainerPort, backendContainerPort))

    // Mount shared PVC for DuckLake data files
    config.volumeClaimName.foreach { pvcName =>
      val volumeMount = new VolumeMount()
      volumeMount.setName("projects")
      volumeMount.setMountPath(config.volumeMountPath)
      container.setVolumeMounts(java.util.List.of(volumeMount))
    }

    // Pod spec
    val podSpec = new PodSpec()
    podSpec.setContainers(java.util.List.of(container))
    podSpec.setRestartPolicy("Never")

    // Add PVC volume if configured
    config.volumeClaimName.foreach { pvcName =>
      val volume = new Volume()
      volume.setName("projects")
      val pvcSource = new PersistentVolumeClaimVolumeSource()
      pvcSource.setClaimName(pvcName)
      volume.setPersistentVolumeClaim(pvcSource)
      podSpec.setVolumes(java.util.List.of(volume))
    }
    config.serviceAccountName.foreach(sa => podSpec.setServiceAccountName(sa))
    if config.imagePullSecrets.nonEmpty then
      val secrets = config.imagePullSecrets.map { s =>
        val ref = new LocalObjectReference()
        ref.setName(s)
        ref
      }.asJava
      podSpec.setImagePullSecrets(secrets)

    // Metadata
    val metadata = new ObjectMeta()
    metadata.setName(pName)
    metadata.setNamespace(ns)
    metadata.setLabels(labels)

    // Pod
    val pod = new Pod()
    pod.setMetadata(metadata)
    pod.setSpec(podSpec)
    pod

  private def buildService(
      sName: String,
      ns: String,
      name: String,
      labels: java.util.Map[String, String],
      podProxyPort: Int
  ): Service =
    val servicePort = new ServicePort()
    servicePort.setName("proxy")
    servicePort.setPort(podProxyPort)
    servicePort.setTargetPort(new IntOrString(podProxyPort))
    servicePort.setProtocol("TCP")

    val serviceSpec = new ServiceSpec()
    serviceSpec.setType(config.serviceType)
    serviceSpec.setSelector(Map("gizmo-instance" -> name).asJava)
    serviceSpec.setPorts(java.util.List.of(servicePort))

    val svcMetadata = new ObjectMeta()
    svcMetadata.setName(sName)
    svcMetadata.setNamespace(ns)
    svcMetadata.setLabels(labels)

    val service = new Service()
    service.setMetadata(svcMetadata)
    service.setSpec(serviceSpec)
    service

  override def start(
      name: String,
      envVars: Map[String, String],
      proxyPort: Int,
      backendPort: Int,
      onExit: () => Unit
  ): Either[String, SpawnResult] =
    try
      val pName = podName(name)
      val sName = serviceName(name)
      val labels = buildLabels(name)
      val ns = config.namespace
      val podProxyPort = config.proxyPort
      val podBackendPort = config.backendPort

      // Create Pod
      val pod = buildPod(pName, ns, labels, envVars, podProxyPort, podBackendPort)
      client.pods().inNamespace(ns).resource(pod).create()
      logger.info(s"Created pod $pName in namespace $ns")

      // Create Service
      val service = buildService(sName, ns, name, labels, podProxyPort)
      client.services().inNamespace(ns).resource(service).create()
      logger.info(s"Created service $sName in namespace $ns")

      // Allocate external port and patch nginx ConfigMap
      val externalPort = allocateExternalPort()
      externalPort.foreach { ep =>
        patchNginxConfigMap(ep, sName)
        instanceExternalPorts.put(name, ep)
      }

      // Wait for pod to be ready
      try
        client
          .pods()
          .inNamespace(ns)
          .withName(pName)
          .waitUntilReady(config.startupTimeoutSeconds.toLong, java.util.concurrent.TimeUnit.SECONDS)
        logger.info(s"Pod $pName is ready")
      catch
        case e: Exception =>
          logger.error(s"Pod $pName did not become ready within ${config.startupTimeoutSeconds}s", e)
          // Clean up nginx ConfigMap entry on failure
          externalPort.foreach { ep =>
            removeNginxConfigMapEntry(ep)
            instanceExternalPorts.remove(name)
            releaseExternalPort(ep)
          }
          client.pods().inNamespace(ns).withName(pName).delete()
          client.services().inNamespace(ns).withName(sName).delete()
          return Left(
            s"Pod did not become ready within ${config.startupTimeoutSeconds} seconds: ${e.getMessage}"
          )

      // Set up watch for unexpected exits
      setupWatch(pName, ns, name, onExit)

      val host = s"$sName.$ns.svc.cluster.local"
      Right(SpawnResult(K8sProcessHandle(pName, sName, ns, name), host, podProxyPort, config.externalHost, externalPort))
    catch
      case e: Exception =>
        logger.error(s"Failed to create K8s resources for '$name'", e)
        Left(s"Failed to start K8s pod: ${e.getMessage}")

  private def setupWatch(pName: String, ns: String, instanceName: String, onExit: () => Unit): Unit =
    val watch = client
      .pods()
      .inNamespace(ns)
      .withName(pName)
      .watch(new Watcher[Pod] {
        override def eventReceived(action: Action, resource: Pod): Unit =
          action match
            case Action.DELETED =>
              logger.warn(s"Pod $pName was deleted unexpectedly")
              onExit()
            case Action.MODIFIED =>
              val phase =
                Option(resource.getStatus).flatMap(s => Option(s.getPhase)).getOrElse("")
              if phase == "Failed" || phase == "Succeeded" then
                logger.warn(s"Pod $pName entered terminal phase: $phase")
                onExit()
            case _ => // ignore

        override def onClose(cause: io.fabric8.kubernetes.client.WatcherException): Unit =
          if cause != null then
            logger.warn(s"Watch for pod $pName closed with error: ${cause.getMessage}")
      })
    watches.put(instanceName, watch)

  override def discoverExisting(onExitFactory: String => () => Unit): List[DiscoveredProcess] =
    val ns = config.namespace
    try
      val pods = client
        .pods()
        .inNamespace(ns)
        .withLabel(managedByLabel, managedByValue)
        .list()
        .getItems
        .asScala
        .toList

      val discovered = pods.flatMap { pod =>
        val podMeta = pod.getMetadata
        val pName = podMeta.getName
        val phase = Option(pod.getStatus).flatMap(s => Option(s.getPhase)).getOrElse("")

        if Set("Failed", "Succeeded", "Unknown").contains(phase) then
          logger.info(s"Cleaning up stale pod $pName in phase '$phase'")
          try
            val labels = Option(podMeta.getLabels).map(_.asScala.toMap).getOrElse(Map.empty)
            val instanceName = labels.getOrElse("gizmo-instance", pName.stripPrefix("gizmo-proxy-"))
            client.pods().inNamespace(ns).withName(pName).delete()
            val sName = serviceName(instanceName)
            client.services().inNamespace(ns).withName(sName).delete()
            instanceExternalPorts.remove(instanceName).foreach { ep =>
              removeNginxConfigMapEntry(ep)
              releaseExternalPort(ep)
            }
          catch case e: Exception =>
            logger.warn(s"Failed to clean up stale pod $pName: ${e.getMessage}")
          None
        else if phase != "Running" then
          logger.info(s"Skipping pod $pName in phase '$phase' during discovery")
          None
        else
          val labels = Option(podMeta.getLabels).map(_.asScala.toMap).getOrElse(Map.empty)
          val instanceName = labels.getOrElse("gizmo-instance", pName.stripPrefix("gizmo-proxy-"))
          val sName = serviceName(instanceName)

          // Verify corresponding service exists
          val svc = client.services().inNamespace(ns).withName(sName).get()
          if svc == null then
            logger.warn(s"Pod $pName has no matching service $sName, skipping")
            None
          else
            // Extract env vars from the first container
            val containerEnvVars = Option(pod.getSpec)
              .flatMap(s => Option(s.getContainers))
              .map(_.asScala.toList)
              .getOrElse(Nil)
              .headOption
              .flatMap(c => Option(c.getEnv))
              .map(_.asScala.toList)
              .getOrElse(Nil)

            val envMap = containerEnvVars.flatMap { ev =>
              Option(ev.getName).zip(Option(ev.getValue))
            }.toMap

            val internalKeys = Set("PROXY_PORT", "PROXY_HOST", "GIZMO_SERVER_HOST", "GIZMO_SERVER_PORT")
            val arguments = envMap.filterNot { case (k, _) => internalKeys.contains(k) }
            val proxyPort = envMap.get("PROXY_PORT").flatMap(_.toIntOption).getOrElse(config.proxyPort)
            val backendPort = envMap.get("GIZMO_SERVER_PORT").flatMap(_.toIntOption).getOrElse(config.backendPort)

            // Re-establish watch
            setupWatch(pName, ns, instanceName, onExitFactory(instanceName))

            val host = s"$sName.$ns.svc.cluster.local"
            val handle = K8sProcessHandle(pName, sName, ns, instanceName)

            // Recover external port from nginx ConfigMap
            val externalPort: Option[Int] = for
              cmName <- config.nginxConfigMapName
              cmNs = config.nginxConfigMapNamespace.getOrElse(config.namespace)
              cm <- Option(client.configMaps().inNamespace(cmNs).withName(cmName).get())
              data <- Option(cm.getData).map(_.asScala.toMap)
              entry <- data.find((_, v) => v.contains(s"/$sName:"))
              port <- entry._1.toIntOption
            yield
              allocatedPorts.add(port)
              instanceExternalPorts.put(instanceName, port)
              port

            logger.info(s"Discovered existing pod $pName (instance=$instanceName, port=$proxyPort, externalPort=${externalPort.getOrElse("none")})")
            Some(DiscoveredProcess(instanceName, handle, host, proxyPort, backendPort, arguments, config.externalHost, externalPort))
      }

      // Clean up orphan nginx ConfigMap entries pointing to non-existent services
      cleanupOrphanConfigMapEntries(ns)

      discovered
    catch
      case e: Exception =>
        logger.error("Failed to discover existing K8s pods", e)
        List.empty

  override def stop(handle: ProcessHandle): Either[String, Unit] =
    handle match
      case K8sProcessHandle(pName, sName, ns, instanceName) =>
        try
          instanceExternalPorts.remove(instanceName).foreach { ep =>
            removeNginxConfigMapEntry(ep)
            releaseExternalPort(ep)
          }
          watches.remove(instanceName).foreach(_.close())
          client.pods().inNamespace(ns).withName(pName).delete()
          client.services().inNamespace(ns).withName(sName).delete()
          logger.info(s"Deleted pod $pName and service $sName in namespace $ns")
          Right(())
        catch
          case e: Exception =>
            logger.error(s"Failed to delete K8s resources: $pName", e)
            Left(s"Failed to stop K8s pod: ${e.getMessage}")
      case _ =>
        Left("KubernetesProcessBackend can only stop K8sProcessHandle instances")

  override def isAlive(handle: ProcessHandle): Boolean =
    handle match
      case K8sProcessHandle(pName, _, ns, _) =>
        try
          val pod = client.pods().inNamespace(ns).withName(pName).get()
          if pod == null then false
          else
            val phase =
              Option(pod.getStatus).flatMap(s => Option(s.getPhase)).getOrElse("")
            phase == "Running" || phase == "Pending"
        catch case _: Exception => false
      case _ => false

  override def stopAll(): Int =
    val ns = config.namespace
    var count = 0
    try
      // Clean up all nginx ConfigMap entries
      instanceExternalPorts.values.foreach(removeNginxConfigMapEntry)
      instanceExternalPorts.clear()
      allocatedPorts.clear()

      watches.values.foreach(_.close())
      watches.clear()

      val pods = client
        .pods()
        .inNamespace(ns)
        .withLabel(managedByLabel, managedByValue)
        .list()
        .getItems
        .asScala

      pods.foreach { pod =>
        val n = pod.getMetadata.getName
        client.pods().inNamespace(ns).withName(n).delete()
        logger.info(s"Deleted pod $n")
        count += 1
      }

      val services = client
        .services()
        .inNamespace(ns)
        .withLabel(managedByLabel, managedByValue)
        .list()
        .getItems
        .asScala

      services.foreach { svc =>
        val n = svc.getMetadata.getName
        client.services().inNamespace(ns).withName(n).delete()
        logger.info(s"Deleted service $n")
      }
    catch
      case e: Exception =>
        logger.error("Error during K8s stopAll", e)

    count

  override def cleanup(): Unit =
    watches.values.foreach(_.close())
    watches.clear()
    client.close()
    logger.info("Kubernetes client closed")
