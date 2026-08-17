package com.zektopic.cctvapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Renders stored detection events with their snapshot thumbnails.
 *
 * Thumbnails are decoded off the main thread and downsampled to roughly the size of the
 * view, so scrolling a few hundred events does not decode full-resolution JPEGs.
 */
class EventsAdapter(
    private val snapshotFileProvider: (String) -> File?,
    private val onEventClick: (DetectionEvent) -> Unit
) : ListAdapter<DetectionEvent, EventsAdapter.EventViewHolder>(DIFF_CALLBACK) {

    companion object {
        private const val THUMBNAIL_TARGET_WIDTH = 192

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DetectionEvent>() {
            override fun areItemsTheSame(oldItem: DetectionEvent, newItem: DetectionEvent) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DetectionEvent, newItem: DetectionEvent) =
                oldItem == newItem
        }
    }

    private val decodeExecutor = Executors.newFixedThreadPool(2)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    class EventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val snapshot: ImageView = view.findViewById(R.id.image_snapshot)
        val type: TextView = view.findViewById(R.id.text_event_type)
        val time: TextView = view.findViewById(R.id.text_event_time)
        val meta: TextView = view.findViewById(R.id.text_event_meta)
        val caption: TextView = view.findViewById(R.id.text_event_caption)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false)
        return EventViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        val event = getItem(position)

        holder.type.text = event.type.replaceFirstChar { it.uppercase() }
        holder.time.text = timeFormat.format(Date(event.startTimeMs))
        holder.meta.text = event.score
            ?.let { "confidence ${(it * 100).toInt()}%" }
            ?: "no confidence recorded"

        // A caption only exists on devices with Gemini Nano, and it arrives seconds
        // after the event, so a row may legitimately have none. Reset on rebind rather
        // than only setting it, or a recycled holder shows the previous row's caption.
        val caption = event.caption
        if (caption.isNullOrBlank()) {
            holder.caption.visibility = View.GONE
            holder.caption.text = null
        } else {
            holder.caption.visibility = View.VISIBLE
            holder.caption.text = caption
        }

        holder.itemView.setOnClickListener { onEventClick(event) }

        // Tag guards against a recycled holder showing the previous row's image.
        holder.snapshot.setImageDrawable(null)
        holder.snapshot.tag = event.id
        loadThumbnail(holder, event.id)
    }

    private fun loadThumbnail(holder: EventViewHolder, eventId: String) {
        decodeExecutor.execute {
            val bitmap = snapshotFileProvider(eventId)?.let { decodeSampled(it) }
            holder.snapshot.post {
                if (holder.snapshot.tag == eventId) {
                    holder.snapshot.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun decodeSampled(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > THUMBNAIL_TARGET_WIDTH) {
                sampleSize *= 2
            }

            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            )
        } catch (e: Exception) {
            android.util.Log.w("EventsAdapter", "Could not decode snapshot ${file.name}", e)
            null
        }
    }

    /** Releases the decode pool. Call from the host Activity's onDestroy. */
    fun shutdown() {
        decodeExecutor.shutdownNow()
    }
}
