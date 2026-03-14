package com.zektopic.cctvapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.zektopic.cctvapp.databinding.ActivityEventsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventsBinding
    private lateinit var eventStore: EventStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        eventStore = EventStore(this)
        setupListeners()
        refreshEventsList()
    }

    override fun onResume() {
        super.onResume()
        refreshEventsList()
    }

    private fun setupListeners() {
        binding.btnRefreshEvents.setOnClickListener {
            refreshEventsList()
        }

        binding.btnNavSettings.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        binding.btnNavEvents.setOnClickListener {
            refreshEventsList()
        }
    }

    private fun refreshEventsList() {
        val events = eventStore.listRecentEvents(50)
        if (events.isEmpty()) {
            binding.textEventsLog.text = getString(R.string.no_events_yet)
            return
        }

        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val lines = events.map { event ->
            val timestamp = formatter.format(Date(event.startTimeMs))
            val score = event.score?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
            val shortId = if (event.id.length > 8) event.id.substring(0, 8) else event.id
            "[$timestamp] ${event.type} score=$score snapshot=${if (event.snapshotFileName != null) "yes" else "no"} clip=${if (event.clipFileName != null) "yes" else "no"} id=$shortId"
        }
        binding.textEventsLog.text = lines.joinToString("\n")
    }
}
