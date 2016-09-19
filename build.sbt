sbtPlugin := true

organization := "com.github.stonexx.sbt"

name := "sbt-play-webpack"

scalaVersion := "2.10.6"

resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  Resolver.jcenterRepo
)

dependencyOverrides += "org.webjars" % "npm" % "3.9.3"

libraryDependencies += "org.webjars.npm" % "lodash" % "4.15.0"

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % sys.props.getOrElse("play.version", "2.5.8"))

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))
