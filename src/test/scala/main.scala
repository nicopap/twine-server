import org.azuni.twineserver.app.{App, AquireInput, SaveChangeInput, LockInput}
import org.azuni.twineserver.storylocker.{LockId, StoryName, StorySummary}
import org.scalatest._
import io.finch._
import io.finch.circe._
import io.circe._
import scala.util.{Try, Success, Failure}
import shapeless.Witness

class ExampleSpec extends FunSuite {

  // NOTE: this (with the file system) is the shared state between all
  // tests in this class
  var serverState = App.init()

  def postAt[In, Out](
      story: String,
      resource: String,
      content: In,
      endpoint: Endpoint[Out],
  )(
      implicit e: Encode.Aux[In, Application.Json],
      w: Witness.Aux[Application.Json],
  ): Output[Out] = {
    val encodedName = StoryName.fromEncoded(story).urlSafe
    val input = Input
      .post(s"/stories/$encodedName/$resource")
      .withBody[Application.Json](content)(e, w)
    //TODO: convert that to Result to convey meaning of wrong path argument
    endpoint(input).awaitOutputUnsafe().get
  }

  def stories(): Output[List[StorySummary]] =
    serverState.listAvailable(Input.get("/stories")).awaitOutputUnsafe().get

  def story(name: String): Output[String] = {
    val encodedName = StoryName.fromEncoded(name).urlSafe
    val input       = Input.get(s"/stories/$encodedName")
    serverState.getContents(input).awaitOutputUnsafe().get
  }
  def open(story: String, user: String): Output[LockId] =
    postAt(story, "open", AquireInput(user), serverState.aquireLock)

  def close(story: String, lock: LockId): Output[Unit] =
    postAt(story, "close", LockInput(lock), serverState.releaseLock)

  def keepup(story: String, lock: LockId): Output[Unit] =
    postAt(story, "keepup", LockInput(lock), serverState.keepOpen)

  def save(
      story: String,
      content: String,
      lock: LockId,
  ): Output[Unit] =
    postAt(
      story,
      "save",
      SaveChangeInput(lock, content),
      serverState.saveChanges,
    )

  // Also initializes the testing suit
  test("Initially, there is no stories on the server") {
    import better.files._
    var storyDirectory = file"stories"
    storyDirectory.delete(swallowIOExceptions = true)
    storyDirectory.createDirectory()
    assert(stories().value == List())
  }
  test(
    "The server responds with a 400 when missformed strings are used as lockid",
  ) {
    val payload = Map(("lock", "ADDD===="), ("file", "éĸĸßßßß···̣̣à···¿¿¿ΩΩ"))
    val output  = postAt("story-name", "save", payload, serverState.saveChanges)
    assert(output.status.code == 400)
  }
  // FIXME: reveals uncaught exception while base64-decoding urls
  test("The server handles correctly malformed story names") {
    val output = open("A%99%99%99malformed̉̉ʂstory%fname", "Some user")
    assert(output.status.code == 400)
  }
  test("The server handles properly story names with slashes") {
    // NOTE: Limitation of the finch framework, unmatched paths are
    // converted into proper "output" in an opaque way
    assertThrows[Exception](open("A/story/name/with/slashed", "Some user"))
  }
  test("When I hold the lock on a file, the content is saved") {
    val lock = open("Classic%20story%20name", "Classic user").value
    save("Classic%20story%20name", "CONTENTCONTENT", lock)
    assert(story("Classic%20story%20name").value == "CONTENTCONTENT")
  }
  test("I cannot open a file that is already locked") {
    open("Changed-story-name", "Thurow Tester")
    val response = open("Changed-story-name", "Thurow Tester")
    assert(response.status.code == 400)
  }
  test("When a story is saved, I can see it in the story list") {
    val lock = open("VisibleSavedStory", "~~$'!").value
    save("VisibleSavedStory", "AAAA", lock)
    val storiesDuring = stories().value
    assert(storiesDuring.find(_.name.urlSafe == "VisibleSavedStory").isDefined)
  }
  test(
    "When a story is locked, I can see the editor in the story list."
      + " It is removed after the story is freed.",
  ) {
    val lock          = open("EditorInStoryList", "%%FANCYUSERNAME%%").value
    val storiesDuring = stories().value
    val thisStory =
      storiesDuring.find(_.name.urlSafe == "EditorInStoryList").get
    assert(thisStory.editor == Some("%%FANCYUSERNAME%%"))
    close("EditorInStoryList", lock)
    val storiesAfter = stories().value
    val thisStoryAfter =
      storiesAfter.find(_.name.urlSafe == "EditorInStoryList").get
    assert(thisStoryAfter.editor == None)
  }
  test("I keep ownership of a story after refreeshing the lock") {
    val lock = open("Another-story-name", "Varied user").value
    keepup("Another-story-name", lock)
    val response = save("Another-story-name", "%%%%%%%%", lock)
    assert(response.status.code == 200)
  }
  test("I can save to file that has been unlocked") {
    val lock = open("lastStoryName", "Ðrôlerie").value
    save("lastStoryName", "999999999", lock)
    close("lastStoryName", lock)
    val lock2 = open("lastStoryName", "Fancy other User").value
    save("lastStoryName", "88888888", lock2)
    assert(story("lastStoryName").value == "88888888")
  }
  test("After closing a file, I can't save to it") {
    val lock = open("finalStoryName", "Ðrôlerie").value
    save("finalStoryName", "999999999", lock)
    close("finalStoryName", lock)
    assert(save("finalStoryName", "7777777", lock).status.code == 400)
  }
}
