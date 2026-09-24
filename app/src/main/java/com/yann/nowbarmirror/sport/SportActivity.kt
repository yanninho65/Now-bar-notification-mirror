package com.yann.nowbarmirror.sport

import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yann.nowbarmirror.R
import com.yann.nowbarmirror.settings.PermissionsActivity

/**
 * "Sport" screen (24/09/2026 — was a tab of MainActivity; API overrides removed the same day):
 * the active Sofascore notifications, "Dernière notification (auto)" first. Tapping a row picks
 * what drives the watch "Score en direct" complication (SofascorePrefs). Rebuilt on every resume.
 */
class SportActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_sport)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        status = findViewById(R.id.sport_status)
        list = findViewById(R.id.sport_list)
        list.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        if (!PermissionsActivity.listenerGranted(this, SofascoreNotificationListenerService::class.java)) {
            status.text = getString(R.string.sport_access_missing)
            list.adapter = null
            return
        }
        val matches = SofascoreNotificationListenerService.listAvailableMatchesIfConnected()
        val items = listOf<SofascorePickerItem>(SofascorePickerItem.Latest) + matches.map { SofascorePickerItem.Match(it) }
        status.text = activeLabel(matches)
        list.adapter = SofascoreHomeAdapter(items, ::isActive, ::onRowSelected)
    }

    private fun activeLabel(matches: List<SofascoreMatchOption>): String {
        val base = if (SofascorePrefs.loadMode(this) == SofascorePrefs.Mode.CHOSEN) {
            "Suivi : ${SofascorePrefs.loadChosenLabel(this) ?: "match choisi"}"
        } else {
            "Suivi : dernière notification"
        }
        return if (matches.isEmpty()) "$base (aucune notif Sofascore active pour l'instant)" else base
    }

    private fun isActive(item: SofascorePickerItem): Boolean = when (item) {
        is SofascorePickerItem.Latest -> SofascorePrefs.loadMode(this) != SofascorePrefs.Mode.CHOSEN
        is SofascorePickerItem.Match ->
            SofascorePrefs.loadMode(this) == SofascorePrefs.Mode.CHOSEN &&
                SofascorePrefs.loadChosenKey(this) == item.option.key
    }

    private fun onRowSelected(item: SofascorePickerItem) {
        when (item) {
            is SofascorePickerItem.Latest -> SofascorePrefs.saveLatest(this)
            is SofascorePickerItem.Match -> SofascorePrefs.saveChosen(
                this, item.option.key, "${item.option.homeTeam} - ${item.option.awayTeam}"
            )
        }
        SofascoreNotificationListenerService.refreshIfConnected()
        render()
    }
}
