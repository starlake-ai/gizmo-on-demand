import sbt.Resolver

object Resolvers {
  val typeSafe = Resolver.url("Typesafe repository", new java.net.URL("https://repo.typesafe.com/typesafe/releases/"))
  val snapshots = Resolver.sonatypeCentralSnapshots
  val allResolvers =
    Seq(Resolver.mavenLocal, snapshots, typeSafe)
}
