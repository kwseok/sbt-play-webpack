# sbt-play-webpack
An play sbt plugin for the webpack module bundler.

Add plugin
----------

Add the plugin to `project/plugins.sbt`.

```scala
addSbtPlugin("io.teamscala.sbt" % "sbt-play-webpack" % "1.0.2")
```

Your project's build file also needs to enable play sbt plugins. For example with build.sbt:

    lazy val root = (project.in file(".")).enablePlugins(PlayScala)

Add webpack as a devDependancy to your package.json file (located at the root of your project):
```json
{
  "devDependencies": {
    "webpack": "^1.13.1"
  }
}
```

Configuration
-------------

```scala
PlayWebpackKeys.config in webpack := [location of config file]
```
(if not set, defaults to baseDirectory / "webpack.config.js")

## License
`sbt-play-webpack` is licensed under the [Apache License, Version 2.0](https://github.com/stonexx/sbt-play-webpack/blob/master/LICENSE)
