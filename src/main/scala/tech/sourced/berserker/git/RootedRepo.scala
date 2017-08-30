package tech.sourced.berserker.git

import java.io.{File, IOException}

import org.apache.hadoop.conf.Configuration
import org.apache.log4j.Logger
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{Config, ObjectId, ObjectReader, Ref}
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import tech.sourced.berserker.FsUtils
import tech.sourced.berserker.spark.MapAccumulator

import scala.collection.JavaConverters._
import scala.collection.mutable


object RootedRepo {
  val thisStage = "Stage: JGitFileIteration"

  def readFile(objId: ObjectId, reader: ObjectReader): Array[Byte] = {
    val obj = reader.open(objId)
    val data = if (obj.isLarge) {
      val buf = Array.ofDim[Byte](20*1024*1024)
      val is = obj.openStream()
      is.read(buf)
      is.close()
      buf
    } else {
      obj.getBytes
    }
    reader.close()
    data
  }
  def gitTree(dotGit: String, hadoopConf: Configuration, skippedRepos: Option[MapAccumulator]): (Option[TreeWalk], Ref, Config) = {
    val log = Logger.getLogger(thisStage)
    log.info(s"Reading bare .git repository from $dotGit")

    val repository = new FileRepositoryBuilder()
        .readEnvironment()
        .setGitDir(new File(dotGit))
        .setMustExist(true)
        .setBare()

    try {
      val git = new Git(repository.build())
      val noneForkOrigHeadRef = getNoneForkOrigRepoHeadRef(git.getRepository.getAllRefs().asScala)

      val objectId = noneForkOrigHeadRef.getObjectId
      val revWalk = new RevWalk(git.getRepository)
      val revCommit = revWalk.parseCommit(objectId)
      revWalk.close()

      val treeWalk = new TreeWalk(git.getRepository)
      treeWalk.setRecursive(true)
      treeWalk.addTree(revCommit.getTree)

      log.info(s"Walking a tree of $dotGit repository at ${noneForkOrigHeadRef.getName}")
      (Some(treeWalk), noneForkOrigHeadRef, git.getRepository.getConfig)

    } catch {
      case e: IOException => log.error(s"${e.getClass.getSimpleName}: skipping repository in $dotGit", e)

      FsUtils.rm(hadoopConf, dotGit)
      skippedRepos.foreach(_.add(e.getClass.getSimpleName -> 1))
      (None, null, null)
    }
  }


  def getNoneForkOrigRepoHeadRef(allRefs: mutable.Map[String, Ref]): Ref = {
    val log = Logger.getLogger(thisStage)
    log.info(s"${allRefs.size} refs found. Picking a ref to head of original none-fork repo")

    val origNoneForkHead = allRefs.get(findHeadRefUsingHeuristics(allRefs.keys))

    log.info(s"Done. ${origNoneForkHead} ref picked")
    return origNoneForkHead.get
  }

  def findHeadRefUsingHeuristics(refs: Iterable[String]): String = {
    // TODO(bzz): pick ref to non-fork orig repo HEAD
    //  right now we do not have orig HEAD refs in RootedRepos, AKA https://github.com/src-d/borges/issues/116
    //    so we always pick 'refs/heads/master' instead

    //  right now `is_fork` is not in RootedRepo config AKA https://github.com/src-d/borges/issues/117
    //    so we sort all refs and always pick first one (will be: filtered by is_fork + heuristics)

    refs.toSeq.sorted.head
  }
}
