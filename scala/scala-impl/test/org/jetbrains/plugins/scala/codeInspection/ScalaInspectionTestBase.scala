package org.jetbrains.plugins.scala
package codeInspection

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.ex.ScopeToolState
import com.intellij.codeInspection.{LocalInspectionEP, LocalInspectionTool}
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.TextRange
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager
import com.intellij.testFramework.EditorTestUtil
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestAdapter
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestAdapter.{findCaretOffset, normalize}
import org.jetbrains.plugins.scala.extensions.executeWriteActionCommand
import org.jetbrains.plugins.scala.util.MarkersUtils
import org.junit.Assert._

import scala.collection.JavaConverters

abstract class ScalaHighlightsTestBase extends ScalaLightCodeInsightFixtureTestAdapter {
  self: ScalaLightCodeInsightFixtureTestAdapter =>

  import ScalaHighlightsTestBase._

  protected val description: String

  protected val fileType: LanguageFileType = ScalaFileType.INSTANCE

  val START = EditorTestUtil.SELECTION_START_TAG
  val END = EditorTestUtil.SELECTION_END_TAG

  protected def descriptionMatches(s: String): Boolean = s == normalize(description)

  protected override def checkTextHasNoErrors(text: String): Unit = {
    val ranges = configureByText(text).actualHighlights.map(_._2)
    assertTrue(
      if (shouldPass) s"Highlights found at: ${ranges.mkString(", ")}." else failingPassed,
      !shouldPass ^ ranges.isEmpty
    )
  }

  protected def checkTextHasError(text: String, allowAdditionalHighlights: Boolean = false): Unit = {
    val TestPrepareResult(expectedRanges, actualHighlights) = configureByText(text)
    val actualRanges = actualHighlights.map(_._2)
    checkTextHasError(expectedRanges, actualRanges, allowAdditionalHighlights)
  }

  protected def checkTextHasError(expectedHighlightRanges: Seq[TextRange],
                                  actualHighlightRanges: Seq[TextRange],
                                  allowAdditionalHighlights: Boolean): Unit = {
    val expectedRangesNotFound = expectedHighlightRanges.filterNot(actualHighlightRanges.contains)
    if (shouldPass) {
      assertTrue(s"Highlights not found: $description", actualHighlightRanges.nonEmpty)
      assertTrue(
        s"Highlights found at: ${actualHighlightRanges.mkString(", ")}, " +
          s"not found: ${expectedRangesNotFound.mkString(", ")}",
        expectedRangesNotFound.isEmpty)
      val duplicatedHighlights = actualHighlightRanges
        .groupBy(identity)
        .mapValues(_.length)
        .toSeq
        .collect { case (highlight, count) if count > 1 => highlight }

      assertTrue(s"Some highlights were duplicated: ${duplicatedHighlights.mkString(", ")}", duplicatedHighlights.isEmpty)
      if (!allowAdditionalHighlights) {
        assertTrue(
          s"Found too many highlights: ${actualHighlightRanges.mkString(", ")}, " +
            s"expected: ${expectedHighlightRanges.mkString(", ")}",
          actualHighlightRanges.length == expectedHighlightRanges.length
        )
      }
    } else {
      assertTrue(failingPassed, actualHighlightRanges.isEmpty)
      assertFalse(failingPassed, expectedRangesNotFound.isEmpty)
    }
  }

  protected def configureByText(text: String): TestPrepareResult = {
    val fileText = normalize(createTestText(text))

    val (_, expectedRanges) =
      MarkersUtils.extractSequentialMarkers(fileText, START, END)

    val (normalizedText, offset) = findCaretOffset(fileText, stripTrailingSpaces = true)

    val fixture = getFixture
    fixture.configureByText(fileType, normalizedText)

    import JavaConverters._
    val highlights = fixture.doHighlighting().asScala
      .filter(it => descriptionMatches(it.getDescription))
    val highlightsWithRanges = highlights
      .filter(checkOffset(_, offset))
      .map(info => (info, highlightedRange(info)))

    TestPrepareResult(expectedRanges, highlightsWithRanges)
  }

  protected def createTestText(text: String): String = text
}

object ScalaHighlightsTestBase {

  case class TestPrepareResult(expectedRanges: Seq[TextRange],
                               actualHighlights: Seq[(HighlightInfo, TextRange)])

  private def highlightedRange(info: HighlightInfo): TextRange =
    new TextRange(info.getStartOffset, info.getEndOffset)

  private def checkOffset(highlightInfo: HighlightInfo, offset: Int): Boolean =
    if (offset == -1) {
      true
    } else {
      val range = highlightedRange(highlightInfo)
      range.containsOffset(offset)
    }
}

abstract class ScalaQuickFixTestBase extends ScalaInspectionTestBase

abstract class ScalaAnnotatorQuickFixTestBase extends ScalaHighlightsTestBase {
  import ScalaAnnotatorQuickFixTestBase.quickFixes

  protected def testQuickFix(text: String, expected: String, hint: String): Unit = {
    val maybeAction = findQuickFix(text, hint)
    assertFalse(s"Quick fix not found: $hint", maybeAction.isEmpty)

    executeWriteActionCommand() {
      maybeAction.get.invoke(getProject, getEditor, getFile)
    }(getProject)

    val expectedFileText = createTestText(expected)
    getFixture.checkResult(normalize(expectedFileText), /*stripTrailingSpaces = */ true)
  }

  protected def checkNotFixable(text: String, hint: String): Unit = {
    val maybeAction = findQuickFix(text, hint)
    assertTrue("Quick fix found.", maybeAction.isEmpty)
  }

  protected def checkIsNotAvailable(text: String, hint: String): Unit = {
    val maybeAction = findQuickFix(text, hint)
    assertTrue("Quick fix not found.", maybeAction.nonEmpty)
    assertTrue("Quick fix is available", maybeAction.forall(action => !action.isAvailable(getProject, getEditor, getFile)))
  }

  private def findQuickFix(text: String, hint: String): Option[IntentionAction] =
    configureByText(text).actualHighlights.map(_._1) match {
      case Seq() => fail("Errors not found.").asInstanceOf[Nothing]
      case seq   => seq.flatMap(quickFixes).find(_.getText == hint)
    }
}

object ScalaAnnotatorQuickFixTestBase {
  private def quickFixes(info: HighlightInfo): Seq[IntentionAction] = {
    import JavaConverters._
    Option(info.quickFixActionRanges).toSeq
      .flatMap(_.asScala)
      .flatMap(pair => Option(pair))
      .map(_.getFirst.getAction)
  }
}

abstract class ScalaInspectionTestBase extends ScalaAnnotatorQuickFixTestBase {

  protected val classOfInspection: Class[_ <: LocalInspectionTool]

  protected override def setUp(): Unit = {
    super.setUp()
    getFixture.enableInspections(classOfInspection)
  }
}

trait ForceInspectionSeverity extends ScalaInspectionTestBase {

  private var oldLevel: HighlightDisplayLevel = _
  protected override def setUp(): Unit = {
    super.setUp()
    val toolState = inspectionToolState
    oldLevel = toolState.getLevel
    toolState.setLevel(forcedInspectionSeverity)
  }

  override def tearDown(): Unit = {
    inspectionToolState.setLevel(oldLevel)
    super.tearDown()
  }

  private def inspectionToolState: ScopeToolState = {
    val profile = ProjectInspectionProfileManager.getInstance(getFixture.getProject).getCurrentProfile
    profile.getToolDefaultState(inspectionEP.getShortName, getFixture.getProject)
  }

  private def inspectionEP =
    LocalInspectionEP.LOCAL_INSPECTION
      .getExtensions
      .find(_.implementationClass == classOfInspection.getCanonicalName)
      .get

  protected def forcedInspectionSeverity: HighlightDisplayLevel
}