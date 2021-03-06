/*
 * Copyright 2020 The Android Open Source Project
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

@file:Suppress("DEPRECATION")

package androidx.compose.ui.platform

import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.DrawLayerModifier
import androidx.compose.ui.Modifier
import androidx.compose.ui.RootMeasureBlocks
import androidx.compose.ui.autofill.Autofill
import androidx.compose.ui.autofill.AutofillTree
import androidx.compose.ui.drawLayer
import androidx.compose.ui.focus.ExperimentalFocus
import androidx.compose.ui.focus.FocusDetailedState
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusModifierImpl
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.DesktopCanvas
import androidx.compose.ui.input.key.ExperimentalKeyInput
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyInputModifier
import androidx.compose.ui.text.input.TextInputService
import androidx.compose.ui.text.platform.FontLoader
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.pointer.PointerInputEvent
import androidx.compose.ui.input.pointer.PointerInputEventProcessor
import androidx.compose.ui.node.ExperimentalLayoutNodeApi
import androidx.compose.ui.node.InternalCoreApi
import androidx.compose.ui.node.LayoutNode
import androidx.compose.ui.node.MeasureAndLayoutDelegate
import androidx.compose.ui.node.OwnedLayer
import androidx.compose.ui.node.Owner
import androidx.compose.ui.semantics.SemanticsModifierCore
import androidx.compose.ui.semantics.SemanticsOwner

@OptIn(
    ExperimentalFocus::class,
    ExperimentalKeyInput::class,
    ExperimentalLayoutNodeApi::class,
    ExperimentalComposeApi::class,
    InternalCoreApi::class
)
class DesktopOwner(
    val container: DesktopOwners
) : Owner {
    private var size: IntSize = IntSize(0, 0)

    // TODO(demin): support RTL
    override val layoutDirection: LayoutDirection = LayoutDirection.Ltr

    private val semanticsModifier = SemanticsModifierCore(
        id = SemanticsModifierCore.generateSemanticsId(),
        mergeAllDescendants = false,
        properties = {}
    )
    // TODO(demin): remove focusModifier after it is removed from AndroidComposeView
    @Suppress("DEPRECATION")
    private val focusModifier = FocusModifierImpl(FocusDetailedState.Inactive)
    private val focusManager: FocusManager = FocusManager()
    private val keyInputModifier = KeyInputModifier(null, null)

    override val root = LayoutNode().also {
        it.measureBlocks = RootMeasureBlocks
        it.modifier = Modifier.drawLayer()
            .then(semanticsModifier)
            .then(focusModifier)
            .then(focusManager.modifier)
            .then(keyInputModifier)
        it.isPlaced = true
    }

    private val snapshotObserver = SnapshotStateObserver { command ->
        command()
    }
    private val pointerInputEventProcessor = PointerInputEventProcessor(root)
    private val measureAndLayoutDelegate = MeasureAndLayoutDelegate(root)

    init {
        container.register(this)
        snapshotObserver.enableStateUpdatesObserving(true)
        root.attach(this)
    }

    fun dispose() {
        snapshotObserver.enableStateUpdatesObserving(false)
        container.unregister(this)
        // we don't need to call root.detach() because root will be garbage collected
    }

    // TODO(demin): pass density here instead of scale canvas (SkiaWindow.kt#initSkija)
    override val density = Density(1f, 1f)

    override val textInputService = TextInputService(container.platformInputService)

    override val fontLoader = FontLoader()

    override val hapticFeedBack = DesktopHapticFeedback()

    override val clipboardManager = DesktopClipboardManager()

    override val textToolbar = DesktopTextToolbar()

    override val semanticsOwner: SemanticsOwner = SemanticsOwner(root)

    override val autofillTree = AutofillTree()

    override val autofill: Autofill? get() = null

    // TODO(demin): implement sending key events from OS
    // (see Ralston Da Silva comment in
    //  [https://android-review.googlesource.com/c/platform/frameworks/support/+/1372126/6])
    // implement also key codes in androidx.compose.ui.input.key.Key
    override fun sendKeyEvent(keyEvent: KeyEvent): Boolean {
        return keyInputModifier.processKeyInput(keyEvent)
    }

    override var showLayoutBounds = false

    override fun pauseModelReadObserveration(block: () -> Unit) =
        snapshotObserver.pauseObservingReads(block)

    override fun requestFocus() = true

    override fun onAttach(node: LayoutNode) = Unit

    override fun onDetach(node: LayoutNode) {
        measureAndLayoutDelegate.onNodeDetached(node)
        snapshotObserver.clear(node)
    }

    override val measureIteration: Long get() = measureAndLayoutDelegate.measureIteration

    override fun measureAndLayout() {
        measureAndLayoutDelegate.measureAndLayout()
        measureAndLayoutDelegate.dispatchOnPositionedCallbacks()
        container.invalidate()
    }

    override fun onInvalidate(layoutNode: LayoutNode) {
        layoutNode.findLayer()?.invalidate()
        container.invalidate()
    }

    override fun onRequestMeasure(layoutNode: LayoutNode) {
        measureAndLayoutDelegate.requestRemeasure(layoutNode)
        container.invalidate()
    }

    override fun onRequestRelayout(layoutNode: LayoutNode) {
        measureAndLayoutDelegate.requestRelayout(layoutNode)
        container.invalidate()
    }

    override fun observeLayoutModelReads(node: LayoutNode, block: () -> Unit) {
        snapshotObserver.observeReads(node, ::onRequestRelayout, block)
    }

    override fun observeMeasureModelReads(node: LayoutNode, block: () -> Unit) {
        snapshotObserver.observeReads(node, ::onRequestMeasure, block)
    }

    private fun observeDrawModelReads(layer: SkijaLayer, block: () -> Unit) {
        snapshotObserver.observeReads(layer, OwnedLayer::invalidate, block)
    }

    override fun createLayer(
        drawLayerModifier: DrawLayerModifier,
        drawBlock: (Canvas) -> Unit,
        invalidateParentLayer: () -> Unit
    ) = SkijaLayer(
        density,
        drawLayerModifier,
        invalidateParentLayer
    ) { canvas ->
        observeDrawModelReads(this) {
            drawBlock(canvas)
        }
    }

    override fun onSemanticsChange() = Unit

    override fun calculatePosition() = IntOffset.Zero

    fun setSize(width: Int, height: Int) {
        val constraints = Constraints(0, width, 0, height)
        this.size = IntSize(width, height)
        measureAndLayoutDelegate.updateRootConstraints(constraints)
    }

    fun draw(canvas: org.jetbrains.skija.Canvas) {
        measureAndLayout()
        root.draw(DesktopCanvas(canvas))
    }

    internal fun processPointerInput(event: PointerInputEvent) {
        measureAndLayout()
        pointerInputEventProcessor.process(event)
    }
}