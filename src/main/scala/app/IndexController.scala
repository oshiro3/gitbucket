package app

import util._
import util.Directory._
import service._
import jp.sf.amateras.scalatra.forms._
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.revwalk.RevWalk
import scala.collection.mutable.ListBuffer
import org.eclipse.jgit.lib.FileMode
import com.google.common.cache.{CacheLoader, CacheBuilder}
import java.util.concurrent.TimeUnit
import model.Issue

class IndexController extends IndexControllerBase 
  with RepositoryService with AccountService with SystemSettingsService with ActivityService
  with ReferrerAuthenticator

trait IndexControllerBase extends ControllerBase { self: RepositoryService 
  with SystemSettingsService with ActivityService
  with ReferrerAuthenticator =>

  val searchForm = mapping(
    "query"      -> trim(text(required)),
    "owner"      -> trim(text(required)),
    "repository" -> trim(text(required))
  )(SearchForm.apply)

  case class SearchForm(query: String, owner: String, repository: String)

  get("/"){
    val loginAccount = context.loginAccount

    html.index(getRecentActivities(),
      getAccessibleRepositories(loginAccount, baseUrl),
      loadSystemSettings(),
      loginAccount.map{ account => getRepositoryNamesOfUser(account.userName) }.getOrElse(Nil)
    )
  }

  post("/search", searchForm){ form =>
    redirect(s"${form.owner}/${form.repository}/search?q=${StringUtil.urlEncode(form.query)}")
  }

  get("/:owner/:repository/search")(referrersOnly { repository =>
    import RepositorySearch._
    val query  = params("q").trim
    val target = params.getOrElse("type", "code")
    val page   = try {
      val i = params.getOrElse("page", "1").toInt
      if(i <= 0) 1 else i
    } catch {
      case e: NumberFormatException => 1
    }


    val SearchResult(files, issues) = cache.get(repository.owner, repository.name, query)

    target.toLowerCase match {
      case "issue" =>
        search.html.issues(issues.map { case (issue, commentCount, content) =>
          IssueSearchResult(
            issue.issueId,
            issue.title,
            issue.openedUserName,
            issue.registeredDate,
            commentCount,
            getHighlightText(content, query)._1)
        }, files.size, query, page, repository)
      case _ =>
        JGitUtil.withGit(getRepositoryDir(repository.owner, repository.name)){ git =>
          val commits = JGitUtil.getLatestCommitFromPaths(git, files.toList.map(_._1), "HEAD")

          search.html.code(files.toList.map { case (path, text) =>
            val (highlightText, lineNumber)  = getHighlightText(text, query)
            FileSearchResult(
              path,
              commits(path).getCommitterIdent.getWhen,
              highlightText,
              lineNumber)
          }, issues.size, query, page, repository)
        }
    }
  })

  private def getHighlightText(content: String, query: String): (String, Int) = {
    val keywords  = StringUtil.splitWords(query.toLowerCase)
    val lowerText = content.toLowerCase
    val indices   = keywords.map(lowerText.indexOf _)

    if(!indices.exists(_ < 0)){
      val lineNumber = content.substring(0, indices.min).split("\n").size - 1
      val highlightText = StringUtil.escapeHtml(content.split("\n").drop(lineNumber).take(5).mkString("\n"))
        .replaceAll("(?i)(" + keywords.map("\\Q" + _ + "\\E").mkString("|") +  ")",
        "<span style=\"background-color: yellow;\">$1</span>")
      (highlightText, lineNumber + 1)
    } else {
      (content.split("\n").take(5).mkString("\n"), 1)
    }
  }

}

case class IssueSearchResult(
  issueId: Int,
  title: String,
  openedUserName: String,
  registeredDate: java.util.Date,
  commentCount: Int,
  highlightText: String)

case class FileSearchResult(
  path: String,
  lastModified: java.util.Date,
  highlightText: String,
  highlightLineNumber: Int)

object RepositorySearch extends IssuesService {

  val CodeLimit  = 10
  val IssueLimit = 10

  case class SearchResult(
    files: List[(String, String)],
    issues: List[(Issue, Int, String)]
   )

  val cache = CacheBuilder.newBuilder()
    .maximumSize(100)
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build(
      new CacheLoader[(String, String, String), SearchResult]() {
        override def load(key: (String, String, String)) = {
          val (owner, repository, query) = key
          val issues = if(query.isEmpty) Nil else searchIssuesByKeyword(owner, repository, query)
          val files  = if(query.isEmpty) Nil else searchRepositoryFiles(owner, repository, query)
          SearchResult(files, issues)
        }
      })

  private def searchRepositoryFiles(owner: String, repository: String, query: String): List[(String, String)] = {
    JGitUtil.withGit(getRepositoryDir(owner, repository)){ git =>
      val revWalk   = new RevWalk(git.getRepository)
      val objectId  = git.getRepository.resolve("HEAD")
      val revCommit = revWalk.parseCommit(objectId)
      val treeWalk  = new TreeWalk(git.getRepository)
      treeWalk.setRecursive(true)
      treeWalk.addTree(revCommit.getTree)

      val keywords = StringUtil.splitWords(query.toLowerCase)
      val list = new ListBuffer[(String, String)]

      while (treeWalk.next()) {
        if(treeWalk.getFileMode(0) != FileMode.TREE){
          JGitUtil.getContent(git, treeWalk.getObjectId(0), false).foreach { bytes =>
            if(FileUtil.isText(bytes)){
              val text      = new String(bytes, "UTF-8")
              val lowerText = text.toLowerCase
              val indices   = keywords.map(lowerText.indexOf _)
              if(!indices.exists(_ < 0)){
                list.append((treeWalk.getPathString, text))
              }
            }
          }
        }
      }
      treeWalk.release
      revWalk.release

      list.toList
    }
  }
}