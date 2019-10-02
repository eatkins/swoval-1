package com.swoval
package watchservice

import java.io.{ FileFilter, IOException }
import java.nio.file.Path
import java.{ lang, util }

import com.swoval.files.FileTreeDataViews.{ Converter, Entry }
import com.swoval.files._
import sbt.Keys._
import sbt._
import sbt.complete.{ DefaultParsers, Parser }

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Try

object CloseWatchPlugin extends AutoPlugin {
  override def trigger = allRequirements
  implicit class PathWatcherOps(val watcher: PathWatcher[_]) {
    def register(path: Path, recursive: Boolean): Unit =
      watcher.register(path, if (recursive) Integer.MAX_VALUE else 0)
    def register(path: Path): Unit = register(path, recursive = true)
  }
  private implicit class FileFilterOps(val fileFilter: FileFilter)
      extends functional.Filter[TypedPath] {
    override def accept(t: TypedPath): Boolean = fileFilter.accept(t.getPath.toFile)
  }
  object autoImport {
    lazy val closeWatchAntiEntropy = settingKey[Duration](
      "Set watch anti-entropy period for source files. For a given file that has triggered a" +
        "build, any updates occuring before the last event time plus this duration will be ignored."
    )
    lazy val closeWatchFileCache = taskKey[FileTreeRepository[Path]]("Set the file cache to use.")
    val closeWatchDisable = settingKey[Boolean]("Disable closewatch")
    lazy val closeWatchLegacyWatchLatency =
      settingKey[Duration]("Set the watch latency of the sbt watch service")
    lazy val closeWatchLegacyQueueSize =
      settingKey[Int](
        "Set the maximum number of watch events to enqueue when using the legacy watch service."
      )
    lazy val closeWatchSourceDiff = taskKey[Unit]("Use default sbt include filters.")
    lazy val closeWatchTransitiveSources =
      inputKey[Seq[SourcePath]](
        "Find all of the watch sources for a particular tasks by looking in the aggregates and " +
          "dependencies of the task. This task differs from watchTransitiveSources because it " +
          "scoped to the first argument the task that is parsed from the input"
      )
    lazy val closeWatchUseDefaultWatchService =
      settingKey[Boolean]("Use the built in sbt watch service.")
  }
  import autoImport._

  private[watchservice] lazy val closeWatchGlobalFileRepository =
    AttributeKey[FileTreeRepository[Path]](
      "closeWatchGlobalFileRepository",
      "A global cache of the file system",
      10
    )
  import scala.language.implicitConversions
  private def defaultSourcesFor(conf: Configuration) = Def.task[Seq[File]] {
    closeWatchFileCache.?.value.foreach { cache =>
      (unmanagedSourceDirectories in conf).value foreach (f => cache.register(f.toPath))
      (managedSourceDirectories in conf).value foreach (f => cache.register(f.toPath))
    }
    Classpaths.concat(unmanagedSources in conf, managedSources in conf).value.distinct.toIndexedSeq
  }
  private def cachedSourcesFor(conf: Configuration, sourcesInBase: Boolean) = Def.task[Seq[File]] {
    def filter(in: FileFilter, ex: FileFilter) = new Compat.FileFilter {
      override def accept(f: File) = in.accept(f) && !ex.accept(f)
      override def toString = s"${Filter.show(in)} && !${Filter.show(ex)}"
    }
    val cache = closeWatchFileCache.?.value.getOrElse(new FileTreeRepository[Path] {
      override def register(
          path: Path,
          maxDepth: Int
      ): functional.Either[IOException, lang.Boolean] = functional.Either.right(false: lang.Boolean)
      override def unregister(path: Path): Unit = {}
      override def addObserver(observer: FileTreeViews.Observer[_ >: Entry[Path]]): Int = -1
      override def removeObserver(handle: Int): Unit = {}
      override def listEntries(
          path: Path,
          maxDepth: Int,
          filter: functional.Filter[_ >: Entry[Path]]
      ): util.List[Entry[Path]] = {
        val view = FileTreeViews.getDefault(true)
        view
          .list(path, maxDepth, AllPassFilter)
          .asScala
          .flatMap { tp =>
            val e = new Entry[Path] {
              override def getTypedPath: TypedPath = tp
              override def getValue: functional.Either[IOException, Path] =
                functional.Either.right(tp.getPath)
              override def compareTo(o: Entry[Path]): Int =
                tp.getPath.compareTo(o.getTypedPath.getPath)
            }
            if (filter.accept(e)) e :: Nil else Nil
          }
          .asJava
      }
      override def list(
          path: Path,
          maxDepth: Int,
          filter: functional.Filter[_ >: TypedPath]
      ): util.List[TypedPath] = {
        val view = FileTreeViews.getDefault(true)
        view.list(path, maxDepth, filter)
      }
      override def addCacheObserver(observer: FileTreeDataViews.CacheObserver[Path]): Int = -1
      override def close(): Unit = {}
    })
    def list(recursive: Boolean, filter: FileFilter): File => Seq[File] =
      (f: File) => {
        val path = f.toPath
        cache.register(path, recursive)
        cache
          .list(path, if (recursive) Integer.MAX_VALUE else 0, t => filter.accept(t.getPath.toFile))
          .asScala
          .map(_.getPath.toFile)
      }

    val unmanagedDirs = (unmanagedSourceDirectories in conf).value.distinct
    val unmanagedIncludeFilter = ((includeFilter in unmanagedSources) in conf).value
    val unmanagedExcludeFilter = ((excludeFilter in unmanagedSources) in conf).value
    val unmanagedFilter = filter(unmanagedIncludeFilter, unmanagedExcludeFilter)

    val baseDirs = if (sourcesInBase) Seq((baseDirectory in conf).value) else Seq.empty
    val baseFilter = filter(unmanagedIncludeFilter, unmanagedExcludeFilter)

    val unmanaged = unmanagedDirs flatMap list(recursive = true, unmanagedFilter)
    val base = baseDirs.flatMap(d => list(recursive = false, baseFilter && nodeFilter(d))(d))
    ((unmanaged ++ base).view ++ (managedSources in conf).value).distinct.toIndexedSeq
  }

  private def nodeFilter(dir: File) = new SimpleFileFilter(f => f.toPath.getParent == dir.toPath)
  private def sourcesFor(conf: Configuration) = Def.taskDyn[Seq[File]] {
    if (closeWatchUseDefaultWatchService.value) defaultSourcesFor(conf)
    else cachedSourcesFor(conf, sourcesInBase.value)
  }
  private def watchSourcesTask(config: ConfigKey) = Def.taskDyn {
    val baseDir = baseDirectory.value
    val include = (includeFilter in unmanagedSources).value
    val exclude = (excludeFilter in unmanagedSources).value

    val baseSources: Seq[Compat.WatchSource] =
      if (sourcesInBase.value && config != ConfigKey(Test.name)) {
        val pathFilter = new functional.Filter[Entry[Path]] {
          override def accept(cacheEntry: Entry[Path]): Boolean = {
            val path = cacheEntry.getTypedPath.getPath
            val f = path.toFile
            path.getParent == baseDir && include.accept(f) && !exclude.accept(f)
          }
          override def toString: String =
            s"""SourceFilter(
               |  base = "$baseDir",
               |  filter =  (_: File).getParent == "$baseDir" && ${Filter
                 .show(include)} && !${Filter.show(exclude)}
               |)""".stripMargin
        }
        Seq(
          Compat
            .makeScopedSource(baseDir.toPath, pathFilter, (baseDirectory in config).scopedKey)
        )
      } else Nil
    val unmanagedSourceDirs = ((unmanagedSourceDirectories in Compile).value ++
      (unmanagedSourceDirectories in Test).value).map(_.toPath)
    val managed = ((managedSources in Compile).value ++ (managedSources in Test).value)
      .filter(d => unmanagedSourceDirs.exists(p => d.toPath startsWith p))
      .toSet
    val managedFilter: Option[Compat.FileFilter] =
      if (managed.isEmpty) None else Some(new SimpleFileFilter(managed.contains))
    Def.task[Seq[Compat.WatchSource]] {
      getSources(config, unmanagedSourceDirectories, unmanagedSources, managedFilter).value ++
        getSources(config, unmanagedResourceDirectories, unmanagedResources, managedFilter).value ++
        baseSources
    }
  }
  def watchSourcesSetting(config: ConfigKey) = {
    watchSources in config := watchSourcesTask(config).value
  }
  def getIncludeFilter(config: ConfigKey): Def.Setting[_] =
    includeFilter in (unmanagedSources in config) := {
      if (closeWatchUseDefaultWatchService.value)
        ("*.java" | "*.scala") && new SimpleFileFilter(_.isFile)
      else
        ExtensionFilter("scala", "java") && new sbt.FileFilter {
          override def accept(pathname: File): Boolean = !pathname.isDirectory
          override def toString = "NotDirectoryFilter"
        }
    }
  lazy val projectSettingsImpl: Seq[Def.Setting[_]] = Seq(
    closeWatchLegacyWatchLatency := 50.milliseconds, // os x file system api buffers events for this duration
    closeWatchLegacyQueueSize := 256, // maximum number of buffered events per watched path
    sources in Compile := sourcesFor(Compile).value,
    sources in Test := sourcesFor(Test).value,
    unmanagedSources := Seq.empty,
    getIncludeFilter(Compile),
    getIncludeFilter(Test),
    includeFilter in unmanagedJars := {
      if (closeWatchUseDefaultWatchService.value)
        "*.jar" | "*.so" | "*.dll" | "*.jnilib" | "*.zip"
      else ExtensionFilter("jar", "so", "dll", "jnilib", "zip")
    },
    watchSources in Compile ++= watchSourcesTask(Compile).value.distinct,
    watchSources in Test ++= watchSourcesTask(Test).value.distinct,
    closeWatchSourceDiff := Def.taskDyn {
      val ref = thisProjectRef.value.project
      val default = (defaultSourcesFor(Compile).value ++ defaultSourcesFor(Test).value).toSet
      val base = sourcesInBase.value
      Def.task {
        val cached =
          (cachedSourcesFor(Compile, base).value ++ cachedSourcesFor(Test, base).value).toSet
        val (cachedExtra, defaultExtra) = (cached diff default, default diff cached)
        def msg(version: String, paths: Set[File]) =
          s"The $version source files in $ref had the following extra paths:\n${paths mkString "\n"}"

        if (cachedExtra.nonEmpty) println(msg("cached", cachedExtra))
        if (defaultExtra.nonEmpty) println(msg("default", defaultExtra))
        if (cachedExtra.isEmpty && defaultExtra.isEmpty)
          println(s"No difference in $ref between sbt default source files and from the cache.")
      }
    }.value
  ) ++ Compat.extraProjectSettings
  override lazy val projectSettings: Seq[Def.Setting[_]] = super.projectSettings ++
    Compat.settings(projectSettingsImpl) ++ Seq(
    closeWatchTransitiveSources := Def
      .inputTaskDyn {
        import complete.DefaultParsers._
        getTransitiveWatchSources(spaceDelimited("<arg>").parsed)
      }
      .evaluated
      .sortBy(_.base),
    watchSources in publish := (watchSources in Compile).value,
    watchSources in publishLocal := (watchSources in Compile).value,
    watchSources in publishM2 := (watchSources in Compile).value
  )
  private def getSources(
      config: ConfigKey,
      key: SettingKey[Seq[File]],
      task: TaskKey[Seq[File]],
      extraExclude: Option[FileFilter] = None
  ) =
    Def.task {
      val dirs = (key in config).value
      val ef = (excludeFilter in task).value
      val include = (includeFilter in task).value
      val exclude = extraExclude match {
        case Some(e) => new SimpleFileFilter(p => ef.accept(p) || e.accept(p))
        case None    => ef
      }
      def filter(dir: File): SourceFilter = {
        val pathFilter = new functional.Filter[Entry[Path]] {
          override def accept(cacheEntry: Entry[Path]): Boolean = {
            val f = cacheEntry.getTypedPath.getPath.toFile
            include.accept(f) && !exclude.accept(f)
          }
          override def toString: String = s"${Filter.show(include)} && !${Filter.show(ef)}"
        }
        new SourceFilter(dir.toPath, pathFilter, key.scopedKey)
      }
      dirs.map(d => Compat.makeSource(d.toPath, filter(d)))
    }
  private def paths(tasks: Seq[ScopedKey[_]]): Def.Initialize[Task[Seq[SourcePath]]] = Def.task {
    val visited = mutable.HashSet.empty[String]
    tasks
      .map { task =>
        Def.ScopedKey(task.scope.task match {
          case Zero => task.scope in task.key
          case _    => task.scope
        }, task.key)
      }
      .distinct
      .flatMap(s => Sources(s, state.value, streams.value.log, visited).distinct.sortBy(_.base))
      .distinct
      .sortBy(_.base)
  }
  private def commands(taskDef: String) = Def.taskDyn {
    val s = state.value
    val parser = Command.combine(s.definedCommands)(s)
    DefaultParsers.parse(taskDef, parser) match {
      case Right(_) => paths(Seq((compile in Project.extract(s).currentRef).scopedKey))
      case _        => throw NoSuchTaskException(taskDef)
    }
  }
  private def getTransitiveWatchSources(taskDef: Seq[String]) = Def.taskDyn {
    val parsed = Parser.result(
      Compat.internal.Act.aggregatedKeyParser(state.value),
      taskDef.headOption.getOrElse("compile")
    )
    parsed.fold(_ => commands(taskDef.mkString(" ").trim), paths)
  }
  private def clearGlobalFileRepository(s: State): Unit = {
    s.get(closeWatchGlobalFileRepository).foreach(_.close())
  }
  override lazy val globalSettings: Seq[Def.Setting[_]] = super.globalSettings ++ Seq(
    closeWatchDisable := {
      val Array(maj, min) = sbtVersion.value.split('.').take(2)
      Try(maj.toInt).getOrElse(0) > 0 && Try(min.toInt).getOrElse(0) > 2
    },
    closeWatchFileCache := state.value
      .get(closeWatchGlobalFileRepository)
      .getOrElse(
        throw new IllegalStateException("Global file repository was not previously registered")
      ),
    closeWatchUseDefaultWatchService := closeWatchDisable.value,
    closeWatchTransitiveSources := Def
      .inputTaskDyn {
        import complete.DefaultParsers._
        getTransitiveWatchSources(spaceDelimited("<arg>").parsed)
      }
      .evaluated
      .sortBy(_.base),
    /*
     * This duration was chosen based on the behavior of saving files with neovim v0.2.2. I found
     * that multiple updates of the file would occur usually in a period no longer than 20
     * milliseconds. This value of the parameter mostly eliminates spurious builds due to these
     * multiple updates.
     */
    closeWatchAntiEntropy := 35.milliseconds,
    onLoad := { state =>
      val extracted = Project.extract(state)
      clearGlobalFileRepository(state)
      val session = extracted.session
      val useDefault = extracted.structure.data.data.exists(
        _._2.entries
          .exists(e => e.key.label == "closeWatchUseDefaultWatchService" && e.value == true)
      )

      if (useDefault) state
      else {
        val filtered = state.definedCommands.filterNot(SimpleCommandMatcher.nameMatches("~"))
        val newSettings = session.original.filterNot { s =>
          s.key.key.label == "watchSources" && !s.definitive && (s.pos match {
            case f: FilePosition => f.path.contains("Defaults.scala")
            case _               => false
          })
        }
        val newState = if (newSettings.lengthCompare(session.original.length) != 0) {
          val newStructure = Compat.reapply(newSettings, extracted.structure, extracted.showKey)
          state
            .put(stateBuildStructure, newStructure)
            .put(sessionSettings, session.copy(original = newSettings))
        } else state
        val fileCache = FileTreeRepositories.get(new Converter[Path] {
          override def apply(typedPath: TypedPath): Path = typedPath.getPath
        }, true)
        newState
          .put(closeWatchGlobalFileRepository, fileCache)
          .copy(definedCommands = filtered :+ Continuously.continuous)
          .addExitHook(clearGlobalFileRepository(state))
      }
    },
    onUnload := { state =>
      state.log.debug(s"Closing internal file cache")
      clearGlobalFileRepository(state)
      state
    }
  )
}
