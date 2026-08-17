package com.zektopic.cctvapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.zektopic.cctvapp.databinding.ActivityEventsBinding

class EventsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventsBinding
    private lateinit var eventStore: EventStore
    private lateinit var adapter: EventsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        eventStore = EventStore.forContext(this)

        adapter = EventsAdapter(
            snapshotFileProvider = { id -> eventStore.getEventSnapshotFile(id) },
            onEventClick = { event -> showEventDetail(event) }
        )
        binding.listEvents.layoutManager = LinearLayoutManager(this)
        binding.listEvents.adapter = adapter

        setupListeners()
        refreshEventsList()
    }

    override fun onResume() {
        super.onResume()
        refreshEventsList()
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.shutdown()
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_refresh) {
                refreshEventsList()
                true
            } else {
                false
            }
        }
    }

    private fun refreshEventsList() {
        val events = eventStore.listRecentEvents(200)
        adapter.submitList(events)

        binding.textEventsCount.text = getString(R.string.events_count, events.size)
        binding.textEventsEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        binding.listEvents.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showEventDetail(event: DetectionEvent) {
        val snapshot = eventStore.getEventSnapshotFile(event.id)
        val imageView = android.widget.ImageView(this).apply {
            adjustViewBounds = true
            if (snapshot != null) {
                setImageBitmap(android.graphics.BitmapFactory.decodeFile(snapshot.absolutePath))
            }
        }

        AlertDialog.Builder(this)
            .setTitle(event.type.replaceFirstChar { it.uppercase() })
            .setView(imageView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
