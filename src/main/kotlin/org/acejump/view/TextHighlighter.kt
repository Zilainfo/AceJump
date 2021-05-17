package org.acejump.view

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.codeInsight.hint.HintUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.HintHint
import com.intellij.ui.JBColor
import com.intellij.ui.LightweightHint
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import it.unimi.dsi.fastutil.ints.IntList
import org.acejump.boundaries.EditorOffsetCache
import org.acejump.config.AceConfig
import org.acejump.immutableText
import org.acejump.input.JumpMode
import org.acejump.isWordPart
import org.acejump.search.SearchQuery
import org.acejump.wordEnd
import org.acejump.wordStart
import java.awt.*
import javax.swing.*
import kotlin.math.max

/**
 * Renders highlights for search occurrences.
 */
internal class TextHighlighter {
  private companion object {
    private const val LAYER = HighlighterLayer.LAST + 1
  }

  private var previousHighlights = mutableMapOf<Editor, Array<RangeHighlighter>>()
  private var previousHint : LightweightHint? = null

  /**
   * Label for the search notification.
   */
  private class NotificationLabel constructor(text: String?) : JLabel(text) {
    init {
      background = HintUtil.getInformationColor()
      foreground = JBColor.foreground()
      this.isOpaque = true
    }
  }
  
  /**
   * Removes all current highlights and re-creates them from scratch. Must be called whenever any of the method parameters change.
   */
  fun render(results: Map<Editor, IntList>, query: SearchQuery, jumpMode: JumpMode) {

    val renderer = when {
      query is SearchQuery.RegularExpression -> RegexRenderer
      jumpMode === JumpMode.TARGET -> SearchedWordWithOutlineRenderer
      else -> SearchedWordRenderer
    }
    
    for ((editor, offsets) in results) {
      val highlights = previousHighlights[editor]
    
      val markup = editor.markupModel
      val document = editor.document
      val chars = editor.immutableText
    
      val modifications = (highlights?.size ?: 0) + offsets.size
      val enableBulkEditing = modifications > 1000
    
      try {
        if (enableBulkEditing) {
          document.isInBulkUpdate = true
        }
    
        highlights?.forEach(markup::removeHighlighter)
        previousHighlights[editor] = Array(offsets.size) { index ->
          val start = offsets.getInt(index)
          val end = start + query.getHighlightLength(chars, start)
      
          markup.addRangeHighlighter(start, end, LAYER, null, HighlighterTargetArea.EXACT_RANGE).apply {
            customRenderer = renderer
          }
        }
      } finally {
        if (enableBulkEditing) document.isInBulkUpdate = false
      }
    }

    if (AceConfig.showSearchNotification) {
      showSearchNotification(results, query, jumpMode)
    }

    for (editor in previousHighlights.keys.toList()) {
      if (!results.containsKey(editor)) {
        previousHighlights.remove(editor)?.forEach(editor.markupModel::removeHighlighter)
      }
    }
  }

  /**
   * Show a notification with the current search text.
   */
  private fun showSearchNotification(results: Map<Editor, IntList>, query: SearchQuery, jumpMode: JumpMode) {
    // clear previous hint
    previousHint?.hide()

    // add notification hint to first editor
    results.keys.first().let {
      val component: JComponent = it.component

      val label1: JLabel = NotificationLabel(" " + CodeInsightBundle.message("incremental.search.tooltip.prefix"))
      label1.font = UIUtil.getLabelFont().deriveFont(Font.BOLD)

      val isRegex = query is SearchQuery.RegularExpression
      val queryText =
        if (isRegex) " ${query.rawText}" else query.rawText[0] + query.rawText.drop(1).toLowerCase()
      val label2: JLabel = NotificationLabel(queryText)

      val label3: JLabel =
        NotificationLabel("Found ${results.values.flatMap { it.asIterable() }.size} results in ${results.keys.size} editors.")

      val panel = JPanel(BorderLayout())
      panel.add(label1, BorderLayout.WEST)
      panel.add(label2, BorderLayout.CENTER)
      panel.add(label3, BorderLayout.EAST)
      panel.border = BorderFactory.createLineBorder(jumpMode.caretColor)

      panel.background = jumpMode.caretColor
      panel.preferredSize = Dimension(
        it.contentComponent.width + label1.preferredSize.width,
        panel.preferredSize.height
      )

      val hint = LightweightHint(panel)

      val x = SwingUtilities.convertPoint(component, 0, 0, component).x
      val y: Int = -hint.component.preferredSize.height
      val p = SwingUtilities.convertPoint(component, x, y, component.rootPane.layeredPane)

      HintManagerImpl.getInstanceImpl().showEditorHint(
        hint,
        it,
        p,
        HintManagerImpl.HIDE_BY_ESCAPE or HintManagerImpl.HIDE_BY_TEXT_CHANGE,
        0,
        false,
        HintHint(it, p).setAwtTooltip(false)
      )
      previousHint = hint
    }
  }

  fun reset() {
    previousHighlights.keys.forEach { it.markupModel.removeAllHighlighters() }
    previousHighlights.clear()
    previousHint?.hide()
  }

  /**
   * Renders a filled highlight in the background of a searched text occurrence.
   */
  private object SearchedWordRenderer: CustomHighlighterRenderer {
    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) =
      drawFilled(g, editor, highlighter.startOffset, highlighter.endOffset)

    private fun drawFilled(g: Graphics, editor: Editor, startOffset: Int, endOffset: Int) {
      val start = EditorOffsetCache.Uncached.offsetToXY(editor, startOffset)
      val end = EditorOffsetCache.Uncached.offsetToXY(editor, endOffset)

      g.color = AceConfig.textHighlightColor
      g.fillRect(start.x, start.y + 1, end.x - start.x, editor.lineHeight - 1)

      g.color = AceConfig.tagBackgroundColor
      g.drawRect(start.x, start.y, end.x - start.x, editor.lineHeight)
    }
  }

  /**
   * Renders a filled highlight in the background of a searched text occurrence, as well as an outline indicating the range of characters
   * that will be selected by [JumpMode.TARGET].
   */
  private object SearchedWordWithOutlineRenderer: CustomHighlighterRenderer {
    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
      SearchedWordRenderer.paint(editor, highlighter, g)

      val chars = editor.immutableText
      val startOffset = highlighter.startOffset

      if (chars.getOrNull(startOffset)?.isWordPart == true)
        drawOutline(
          g, editor, chars.wordStart(startOffset),
          chars.wordEnd(startOffset) + 1
        )
    }

    private fun drawOutline(g: Graphics, editor: Editor, startOffset: Int, endOffset: Int) {
      val start = EditorOffsetCache.Uncached.offsetToXY(editor, startOffset)
      val end = EditorOffsetCache.Uncached.offsetToXY(editor, endOffset)

      g.color = AceConfig.targetModeColor
      g.drawRect(max(0, start.x - JBUI.scale(1)), start.y, end.x - start.x + JBUI.scale(2), editor.lineHeight)
    }
  }

  /**
   * Renders a filled highlight in the background of the first highlighted position. Used for regex search queries.
   */
  private object RegexRenderer: CustomHighlighterRenderer {
    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) =
      drawSingle(g, editor, highlighter.startOffset)

    private fun drawSingle(g: Graphics, editor: Editor, offset: Int) {
      val pos = EditorOffsetCache.Uncached.offsetToXY(editor, offset)
      val char = editor.immutableText.getOrNull(offset)
        ?.takeUnless { it == '\n' || it == '\t' } ?: ' '
      val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
      val lastCharWidth = editor.component.getFontMetrics(font).charWidth(char)

      g.color = AceConfig.textHighlightColor
      g.fillRect(pos.x, pos.y + 1, lastCharWidth, editor.lineHeight - 1)

      g.color = AceConfig.tagBackgroundColor
      g.drawRect(pos.x, pos.y, lastCharWidth, editor.lineHeight)
    }
  }
}
