/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.ui.core

import android.content.Context
import androidx.ui.engine.geometry.Offset
import androidx.ui.engine.text.TextAlign
import androidx.ui.engine.text.TextDirection
import androidx.ui.painting.TextSpan
import androidx.ui.painting.TextStyle
import androidx.ui.rendering.box.BoxConstraints
import androidx.ui.rendering.paragraph.RenderParagraph
import androidx.ui.rendering.paragraph.TextOverflow
import com.google.r4a.Ambient
import com.google.r4a.Children
import com.google.r4a.Component
import com.google.r4a.composer

/**
 * Text Widget Crane version.
 *
 * The Text widget displays text that uses multiple different styles. The text to display is
 * described using a tree of [TextSpan] objects, each of which has an associated style that is used
 * for that subtree. The text might break across multiple lines or might all be displayed on the
 * same line depending on the layout constraints.
 */
// TODO(migration/qqd): Add tests when text widget system is mature and testable.
class Text() : Component() {
    /** The text to display. */
    lateinit var text: TextSpan
    /** How the text should be aligned horizontally. */
    var textAlign: TextAlign = TextAlign.START
    /** The directionality of the text. */
    var textDirection: TextDirection = TextDirection.LTR
    /**
     *  Whether the text should break at soft line breaks.
     *  If false, the glyphs in the text will be positioned as if there was unlimited horizontal
     *  space.
     *  If [softWrap] is false, [overflow] and [textAlign] may have unexpected effects.
     */
    var softWrap: Boolean = true
    /** How visual overflow should be handled. */
    var overflow: TextOverflow = TextOverflow.CLIP
    /** The number of font pixels for each logical pixel. */
    var textScaleFactor: Float = 1.0f
    /**
     *  An optional maximum number of lines for the text to span, wrapping if necessary.
     *  If the text exceeds the given number of lines, it will be truncated according to [overflow]
     *  and [softWrap].
     *  The value may be null. If it is not null, then it must be greater than zero.
     */
    var maxLines: Int? = null

    override fun compose() {
        assert(text != null)
        val context = composer.composer.context

        <CurrentTextStyleAmbient.Consumer> style ->
            val mergedStyle = style.merge(text.style)
            // Make a wrapper to avoid modifying the style on the original element
            val styledText = TextSpan(style = mergedStyle, children = listOf(text))
            <DensityProvider> density ->
                <Semantics
                    label=text.toString()>
                    <MeasureBox> constraints, measureOperations ->
                        val renderParagraph = RenderParagraph(
                            text = styledText,
                            textAlign = textAlign,
                            textDirection = textDirection,
                            softWrap = softWrap,
                            overflow = overflow,
                            textScaleFactor = textScaleFactor,
                            maxLines = maxLines
                        )

                        // TODO(Migration/siyamed): This is temporary and should be removed when resource
                        // system is resolved.
                        attachContextToFont(styledText, context)

                        val boxConstraints = BoxConstraints(
                            constraints.minWidth.toPx(density),
                            constraints.maxWidth.toPx(density),
                            constraints.minHeight.toPx(density),
                            constraints.maxHeight.toPx(density)
                        )
                        renderParagraph.performLayout(boxConstraints)
                        measureOperations.collect {
                            <Draw> canvas, parent ->
                                renderParagraph.paint(canvas, Offset(0.0f, 0.0f))
                            </Draw>
                        }
                        measureOperations.layout(
                            renderParagraph.width.toDp(density),
                            renderParagraph.height.toDp(density)
                        ) {}
                    </MeasureBox>
                </Semantics>
            </DensityProvider>
        </CurrentTextStyleAmbient.Consumer>
    }

    private fun attachContextToFont(
        text: TextSpan,
        context: Context
    ) {
        text.visitTextSpan() {
            it.style?.fontFamily?.let {
                it.context = context
            }
            true
        }
    }
}

internal val CurrentTextStyleAmbient = Ambient<TextStyle>("current text style") {
    TextStyle()
}

/**
 * This component is used to set the current value of the Text style ambient. The given style will
 * be merged with the current style values for any missing attributes. Any [Text]
 * components included in this component's children will be styled with this style unless
 * styled explicitly.
 */
// TODO(clara): Make this a function instead of a class when cross module is solved
class CurrentTextStyleProvider(@Children val children: () -> Unit) : Component() {
    var value: TextStyle? = null

    override fun compose() {
        <CurrentTextStyleAmbient.Consumer> style ->
            val mergedStyle = style.merge(value)
            <CurrentTextStyleAmbient.Provider value=mergedStyle>
                <children />
            </CurrentTextStyleAmbient.Provider>
        </CurrentTextStyleAmbient.Consumer>
    }
}

/**
 * This component is used to read the current value of the Text style ambient. Any [Text]
 * components included in this component's children will be styled with this style unless
 * styled explicitly.
 */
// TODO(clara): Make this a function instead of a class when cross module is solved
class CurrentTextStyle(@Children var children: (style: TextStyle) -> Unit) : Component() {

    override fun compose() {
        <CurrentTextStyleAmbient.Consumer> style ->
            <children style />
        </CurrentTextStyleAmbient.Consumer>
    }
}