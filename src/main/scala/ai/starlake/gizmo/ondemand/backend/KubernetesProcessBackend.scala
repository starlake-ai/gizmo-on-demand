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
  private val managedByLabel = "managed-by"
  private val managedByValue = "gizmo-process-manager"

  private def podName(name: String): String = s"gizmo-proxy-$name"
  private def serviceName(name: String): String = s"gizmo-proxy-$name"

  private def buildLabels(name: String): java.util.Map[String, String] =
    val labels = new java.util.HashMap[String, String]()
    labels.put(managedByLabel, managedByValue)
    labels.put("gizmo-instance", name)
    config.labels.foreach { case (k, v) => labels.put(k, v) }
    labels

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
      ("GIZMO_SERVER_PORT", podBackendPort.toString)
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

    // Container
    val container = new Container()
    container.setName("gizmo-proxy")
    container.setImage(config.imageName)
    container.setImagePullPolicy(config.imagePullPolicy)
    container.setEnv(containerEnvVars)
    container.setPorts(java.util.List.of(proxyContainerPort, backendContainerPort))

    // Pod spec
    val podSpec = new PodSpec()
    podSpec.setContainers(java.util.List.of(container))
    podSpec.setRestartPolicy("Never")
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
          client.pods().inNamespace(ns).withName(pName).delete()
          client.services().inNamespace(ns).withName(sName).delete()
          return Left(
            s"Pod did not become ready within ${config.startupTimeoutSeconds} seconds: ${e.getMessage}"
          )

      // Set up watch for unexpected exits
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

      watches.put(name, watch)

      val host = s"$sName.$ns.svc.cluster.local"
      Right(SpawnResult(K8sProcessHandle(pName, sName, ns), host, podProxyPort))
    catch
      case e: Exception =>
        logger.error(s"Failed to create K8s resources for '$name'", e)
        Left(s"Failed to start K8s pod: ${e.getMessage}")

  override def stop(handle: ProcessHandle): Either[String, Unit] =
    handle match
      case K8sProcessHandle(pName, sName, ns) =>
        try
          val instanceName = pName.stripPrefix("gizmo-proxy-")
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
      case K8sProcessHandle(pName, _, ns) =>
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
