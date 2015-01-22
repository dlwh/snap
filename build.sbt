organization := "org.scalanlp"

name := "gust"

version := "0.1-SNAPSHOT"

scalaVersion := "2.11.5"

crossScalaVersions  := Seq("2.11.5", "2.10.4")

libraryDependencies ++= Seq(
  "junit" % "junit" % "4.5" % "test",
  "org.scalanlp" %% "breeze-macros" % "0.3.1" % "compile",
  "org.scalanlp" %% "breeze" % "0.9",
  "org.scalatest" %% "scalatest" % "2.1.3" % "test",
  //"com.nativelibs4java" % "javacl" % "1.0-SNAPSHOT",
   "com.nativelibs4java" % "bridj" % "0.6.2",
  "org.scalacheck" %% "scalacheck" % "1.11.3" % "test",
  "org.scalanlp" % "jcublas2" % "0.5.5",
  "org.scalanlp" % "jcurand" % "0.5.5"
)

libraryDependencies <++= scalaVersion { v =>
  if(v.startsWith("2.11")) 
    Seq("org.scala-lang.modules" % "scala-xml_2.11" % "1.0.1" % "test")
  else
  Seq.empty
}

 fork := true

javaOptions ++= Seq("-Xmx4g", "-Xrunhprof:cpu=samples,depth=12")

resolvers ++= Seq(
  "Scala Tools Snapshots" at "http://scala-tools.org/repo-snapshots/",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"
)


testOptions in Test += Tests.Argument("-oDF")

addCompilerPlugin("org.scalamacros" %% "paradise" % "2.1.0-M1" cross CrossVersion.full)
