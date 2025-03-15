package com.pr0gramm.app.ui

import android.content.Context
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.pr0gramm.app.Logger
import com.pr0gramm.app.seconds
import com.pr0gramm.app.util.directName
import com.pr0gramm.app.util.postOrSetValue
import kotlinx.coroutines.*
import kotlin.reflect.KMutableProperty0

class PaginationController(
        private val pagination: Pagination<*>,
        private val headOffset: Int = 12,
        private val tailOffset: Int = 12) {

    fun hit(position: Int, size: Int) {
        if (position < headOffset) {
            pagination.loadAtHead()
        }

        if (size - position - 1 < tailOffset) {
            pagination.loadAtTail()
        }
    }
}

class Pagination<E : Any>(private val baseScope: CoroutineScope, private val loader: Loader<E>) {

    private val logger = Logger("Pagination(${javaClass.directName})")

    private var headState: EndState<E> = EndState()
    private var tailState: EndState<E> = EndState()

    private val job = SupervisorJob()
    private val scope get() = baseScope + job

    private val mutableUpdates = MutableLiveData<Update<E>>()

    val state: State<E> get() = State(headState, tailState)

    // publishes updates to this pagination
    val updates: LiveData<Update<E>> = mutableUpdates

    /**
     * Loads the first page if no data is currently available
     */
    fun initialize(headState: EndState<E> = EndState(), tailState: EndState<E> = EndState(hasMore = true)) {
        // clear any pending co-routines
        job.cancelChildren()

        this.headState = headState
        this.tailState = tailState

        publishStateChange()

        if (tailState.value != null) {
            this.loadAtTail()
        }
    }

    @MainThread
    fun loadAtHead() {
        if (headState.hasMore && !headState.loading) {
            logger.debug { "loadAtHead() was triggered" }
            load(loader::loadBefore, this::headState)
        }
    }

    @MainThread
    fun loadAtTail() {
        if (tailState.hasMore && !tailState.loading) {
            logger.debug { "loadAtTail() was triggered" }
            load(loader::loadAfter, this::tailState)
        }
    }

    private fun load(
            loadCallback: suspend (previousValue: E?) -> Page<E>,
            endStateRef: KMutableProperty0<EndState<E>>) {

        val previousStateWasError = endStateRef.get().error != null

        // update state first
        endStateRef.set(endStateRef.get().copy(loading = true, error = null))

        scope.launch {
            try {
                if (previousStateWasError) {
                    logger.debug { "Was in error state, delay loading" }
                    delay(1.seconds.inMillis)
                }

                logger.debug { "Start loading" }
                val (data, endValue) = loadCallback(endStateRef.get().value)

                logger.debug { "Loading finished, updating state" }
                endStateRef.set(EndState(value = endValue, hasMore = endValue != null))

                // publish the current state
                publishContent(data)

            } catch (err: CancellationException) {
                logger.warn { "Loading was canceled." }
                endStateRef.set(endStateRef.get().copy(loading = false, error = null))
                publishStateChange()

            } catch (err: Exception) {
                logger.warn { "Loading failed" }
                endStateRef.set(endStateRef.get().copy(loading = false, error = err))
                publishStateChange()
            }
        }
    }

    private fun publishStateChange() {
        publishContent(listOf())
    }

    private fun publishContent(data: List<E>) {
        mutableUpdates.postOrSetValue(Update(state, data))
    }

    data class Update<E>(val state: State<E>, val newValues: List<E>)

    data class State<E>(
            val headState: EndState<E> = EndState(),
            val tailState: EndState<E> = EndState())

    data class EndState<E>(
            val error: Exception? = null,
            val value: E? = null,
            val loading: Boolean = false,
            val hasMore: Boolean = false)

    data class Page<E>(val values: List<E>, val endValue: E? = null) {
        companion object {
            fun <E> atHead(values: List<E>, hasMore: Boolean): Page<E> {
                return Page(values, values.firstOrNull()?.takeIf { hasMore })
            }

            fun <E> atTail(values: List<E>, hasMore: Boolean): Page<E> {
                return Page(values, values.lastOrNull()?.takeIf { hasMore })
            }
        }
    }

    abstract class Loader<E> {
        open suspend fun loadAfter(currentValue: E?): Page<E> {
            return Page(listOf())
        }

        open suspend fun loadBefore(currentValue: E?): Page<E> {
            return Page(listOf())
        }
    }
}

fun addEndStateToValues(
        context: Context, values: MutableList<in Any>, tailState: Pagination.EndState<*>,
        ifEmptyValue: Any? = null) {

    when {
        tailState.error != null ->
            values += ErrorAdapterDelegate.errorValueOf(context, tailState.error)

        tailState.hasMore ->
            values += Loading()
    }

    if (ifEmptyValue != null && values.isEmpty())
        values += ifEmptyValue
}