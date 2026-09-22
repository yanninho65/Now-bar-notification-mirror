package com.yann.nowbarmirror

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.yann.nowbarmirror.databinding.ActivityMainBinding
import com.yann.nowbarmirror.settings.AppSelectionActivity
import com.yann.nowbarmirror.sport.LiveTennisApi
import com.yann.nowbarmirror.sport.MatchResult
import com.yann.nowbarmirror.sport.MatchesAdapter
import com.yann.nowbarmirror.sport.SimpleListAdapter
import com.yann.nowbarmirror.sport.SofascoreApiOverride
import com.yann.nowbarmirror.sport.SofascoreApiOverridePrefs
import com.yann.nowbarmirror.sport.SofascoreHomeAdapter
import com.yann.nowbarmirror.sport.SofascoreMatchOption
import com.yann.nowbarmirror.sport.SofascoreNotificationListenerService
import com.yann.nowbarmirror.sport.SofascorePickerItem
import com.yann.nowbarmirror.sport.SofascorePrefs
import com.yann.nowbarmirror.sport.SportsDbApi
import com.yann.nowbarmirror.sport.TennisApiKeyPrefs
import com.yann.nowbarmirror.sport.TennisPlayerResult
import kotlinx.coroutines.launch

/**
 * FUSIONNÉ le 17/09/2026 avec Sport Watch Complication (voir README.md,
 * section "Fusion avec Sport Watch Complication") : un nouvel onglet
 * "Sport" traite Sofascore À PART de l'écran d'accueil historique de Now
 * Bar Mirror (mirroring générique, onglet "Accueil"), comme demandé par
 * Yann. Les deux écrans partagent la même Activity/le même layout
 * (activity_main.xml, TabLayout basculant home_tab_content/
 * sport_tab_content) mais gardent chacun leur logique propre : la partie
 * "Accueil" ci-dessous est inchangée depuis avant la fusion ; la partie
 * "Sport" est reprise telle quelle (mêmes noms de méthodes/champs) de
 * Sport-watch-complication/mobile/MainActivity.kt, package
 * com.yann.nowbarmirror.sport.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ---- Onglet Sport (repris de Sport Watch Complication) ----------------

    /** Quelle API interroger — voir LiveTennisApi.kt et SportsDbApi.kt. */
    private enum class ApiMode { SPORTS_DB, LIVE_TENNIS }
    private var apiMode = ApiMode.SPORTS_DB

    /**
     * Sport interrogé DANS TheSportsDB (qui couvre plusieurs sports, pas
     * que le foot) — sert à filtrer les recherches côté client via le
     * champ `strSport` de l'API (voir SportsDbApi.searchTeams/
     * searchPlayers/searchLeagues). N'a pas de sens pour Live Tennis API
     * (mono-sport, tennis implicite). [apiValue] est le libellé exact
     * attendu par TheSportsDB pour ce sport, ou null pour [ALL] (aucun
     * filtre).
     */
    private enum class SportsDbSport(val label: String, val apiValue: String?) {
        ALL("Tous sports", null),
        SOCCER("Football", "Soccer"),
        BASKETBALL("Basketball", "Basketball"),
        HANDBALL("Handball", "Handball"),
        RUGBY("Rugby", "Rugby"),
        VOLLEYBALL("Volleyball", "Volleyball")
    }
    private var sportsDbSport = SportsDbSport.ALL

    private enum class SearchMode { TEAM, PLAYER, LEAGUE }
    private var searchMode = SearchMode.TEAM

    /**
     * Clé de la notification Sofascore pour laquelle l'écran de recherche
     * API est actuellement ouvert (voir [openApiPickerFor]) — `null` quand
     * l'écran d'accueil Sport est affiché. C'est CETTE clé qui reçoit
     * l'override enregistré par [showOverrideFieldsDialog] une fois un
     * match choisi.
     */
    private var pendingOverrideNotifKey: String? = null

    // Le refus de cette permission n'empêche pas le repli Sofascore de
    // fonctionner : seul l'affichage de notifications système par l'app
    // elle-même serait limité (comportement standard Android 13+, voir
    // doc POST_NOTIFICATIONS) — utile pour la notification persistante
    // d'ApiOverrideFollowService pendant un sondage API.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 36 forces edge-to-edge: content draws behind the status/nav bars by
        // default. Without this, the title ends up hidden under the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupHomeTab()
        setupTabLayout()
        setupSportTab()

        selectTab(0)
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onStart() {
        super.onStart()
        // Rafraîchit le libellé/l'état de l'onglet Sport au retour de
        // l'écran système (Yann vient peut-être d'accorder l'accès depuis
        // Paramètres > Notifications) — seulement si l'accueil Sport est
        // affiché, pour ne pas interrompre une recherche API en cours si
        // l'Activity a juste perdu puis repris le focus sans vraiment être
        // quittée.
        if (pendingOverrideNotifKey == null) showHomeState()
    }

    // ---- Onglet "Accueil" (mirroring générique, inchangé) ------------------

    private fun setupHomeTab() {
        binding.accessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.notifButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
            }
        }

        // NEW 22/09/2026 (Yann : "Aff. sur tél." doit ouvrir directement, pas juste poser une
        // notification à taper — voir NowBarWidgetProvider.openEntry) — n'existe que sur
        // Android 14+ : avant ça, USE_FULL_SCREEN_INTENT est une permission normale accordée
        // automatiquement à l'installation, ce bouton n'aurait alors aucun écran système à
        // ouvrir. Sur 14+, seules les apps téléphonie/alarme l'ont par défaut ; toute autre app
        // doit passer par cet écran dédié pour que Yann l'accorde lui-même.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            binding.fullScreenIntentButton.visibility = View.VISIBLE
            binding.fullScreenIntentDivider.visibility = View.VISIBLE
            binding.fullScreenIntentStatusText.visibility = View.VISIBLE
            binding.fullScreenIntentButton.setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                )
            }
        }

        binding.appSelectionButton.setOnClickListener {
            startActivity(Intent(this, AppSelectionActivity::class.java))
        }
    }

    private fun refreshStatus() {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        binding.statusText.text = if (enabled) getString(R.string.status_enabled) else getString(R.string.status_disabled)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fullScreenAllowed = getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            binding.fullScreenIntentStatusText.text = if (fullScreenAllowed) {
                getString(R.string.full_screen_intent_enabled)
            } else {
                getString(R.string.full_screen_intent_disabled)
            }
        }
    }

    // ---- Bascule d'onglet ---------------------------------------------------

    private fun setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = selectTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun selectTab(position: Int) {
        val sportSelected = position == 1
        binding.homeTabContent.visibility = if (sportSelected) View.GONE else View.VISIBLE
        binding.sportTabContent.visibility = if (sportSelected) View.VISIBLE else View.GONE
        if (sportSelected) {
            if (pendingOverrideNotifKey == null) showHomeState()
        } else {
            refreshStatus()
        }
    }

    // ---- Onglet "Sport" (repris de Sport Watch Complication) ---------------

    /**
     * Sofascore est TOUJOURS la base de l'onglet Sport. À l'ouverture,
     * [showHomeState] affiche la liste des notifications Sofascore actives
     * ("Dernière notification" toujours en tête). Taper une ligne la
     * choisit comme repli actif. Taper le bouton "API" d'une ligne (une
     * notif précise, pas "Dernière notification") ouvre [showSearchState]
     * — l'écran de recherche TheSportsDB/Live Tennis — ciblé sur CETTE
     * notification précise ([pendingOverrideNotifKey]) : sélectionner un
     * match y ouvre [showOverrideFieldsDialog] (score et/ou période) puis
     * enregistre un override (voir SofascoreApiOverridePrefs) plutôt que
     * de démarrer un suivi global.
     */
    private fun setupSportTab() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        binding.buttonSearch.setOnClickListener { runSearch() }
        binding.editSearch.setOnEditorActionListener { _, _, _ ->
            runSearch()
            true
        }
        binding.buttonBackToSearch.setOnClickListener { showHomeState() }
        setupSearchClearButton()

        // Repli Sofascore (voir SofascoreNotificationListenerService) : l'accès
        // aux notifications est une permission spéciale, non demandable au
        // runtime comme POST_NOTIFICATIONS — seul un raccourci vers l'écran
        // système est possible, Yann doit l'activer lui-même.
        binding.buttonNotificationAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Question 1 : quelle API. Repart d'un état de recherche propre à
        // chaque changement — le sélecteur de sport et le sélecteur
        // équipe/joueur/ligue n'ont pas de sens en tennis, et une liste
        // de résultats de l'API précédente resterait affichée sinon.
        binding.radioApi.setOnCheckedChangeListener { _, checkedId ->
            apiMode = if (checkedId == R.id.radioApiTennis) ApiMode.LIVE_TENNIS else ApiMode.SPORTS_DB
            showSearchState()
        }

        // Question 2 (TheSportsDB uniquement) : quel sport. "Tous sports"
        // (par défaut) ne filtre rien — voir SportsDbSport.
        binding.radioSportsDbSport.setOnCheckedChangeListener { _, checkedId ->
            sportsDbSport = when (checkedId) {
                R.id.radioSdbSoccer -> SportsDbSport.SOCCER
                R.id.radioSdbBasketball -> SportsDbSport.BASKETBALL
                R.id.radioSdbHandball -> SportsDbSport.HANDBALL
                R.id.radioSdbRugby -> SportsDbSport.RUGBY
                R.id.radioSdbVolleyball -> SportsDbSport.VOLLEYBALL
                else -> SportsDbSport.ALL
            }
            showSearchState()
        }

        binding.radioSearchMode.setOnCheckedChangeListener { _, checkedId ->
            searchMode = when (checkedId) {
                R.id.radioModePlayer -> SearchMode.PLAYER
                R.id.radioModeLeague -> SearchMode.LEAGUE
                else -> SearchMode.TEAM
            }
            binding.editSearch.hint = when (searchMode) {
                SearchMode.TEAM -> "Nom d'équipe (ex. PSG)"
                SearchMode.PLAYER -> "Nom de joueur (ex. Mbappé)"
                SearchMode.LEAGUE -> "Nom de ligue (ex. Ligue 1)"
            }
        }

        // Clé Live Tennis API saisie ICI, jamais dans le code (dépôt
        // GitHub public) — voir TennisApiKeyPrefs.kt.
        binding.buttonSaveTennisApiKey.setOnClickListener {
            val key = binding.editTennisApiKey.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                Toast.makeText(this, "Colle d'abord ta clé", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            TennisApiKeyPrefs.save(this, key)
            Toast.makeText(this, "Clé enregistrée", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Écran d'accueil de l'onglet Sport : liste des notifications
     * Sofascore actives, "Dernière notification (auto)" toujours en tête.
     * Reconstruit à chaque appel, donc toujours à jour au moment où
     * l'écran est affiché (pas de rafraîchissement live pendant que l'app
     * reste ouverte dessus).
     */
    private fun showHomeState() {
        pendingOverrideNotifKey = null
        binding.searchControls.visibility = View.GONE

        val granted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (!granted) {
            binding.textStatus.text =
                "Active d'abord l'accès aux notifications ci-dessus pour voir tes matchs Sofascore"
            binding.recyclerView.adapter = null
            return
        }

        val matches = SofascoreNotificationListenerService.listAvailableMatchesIfConnected()
        val items = mutableListOf<SofascorePickerItem>(SofascorePickerItem.Latest)
        items += matches.map { SofascorePickerItem.Match(it) }

        binding.textStatus.text = activeReplyLabel(matches)
        binding.recyclerView.adapter = SofascoreHomeAdapter(
            items = items,
            isActive = ::isActiveReplyItem,
            onRowClicked = ::onSofascoreRowSelected,
            onConfigureApi = ::openApiPickerFor
        )
    }

    private fun activeReplyLabel(matches: List<SofascoreMatchOption>): String {
        val base = if (SofascorePrefs.loadMode(this) == SofascorePrefs.Mode.CHOSEN) {
            "Repli actif : ${SofascorePrefs.loadChosenLabel(this) ?: "match choisi"}"
        } else {
            "Repli actif : dernière notification"
        }
        return if (matches.isEmpty()) "$base (aucune notif Sofascore active pour l'instant)" else base
    }

    private fun isActiveReplyItem(item: SofascorePickerItem): Boolean = when (item) {
        is SofascorePickerItem.Latest -> SofascorePrefs.loadMode(this) != SofascorePrefs.Mode.CHOSEN
        is SofascorePickerItem.Match ->
            SofascorePrefs.loadMode(this) == SofascorePrefs.Mode.CHOSEN &&
                SofascorePrefs.loadChosenKey(this) == item.option.key
    }

    private fun onSofascoreRowSelected(item: SofascorePickerItem) {
        when (item) {
            is SofascorePickerItem.Latest -> SofascorePrefs.saveLatest(this)
            is SofascorePickerItem.Match -> SofascorePrefs.saveChosen(
                this, item.option.key, "${item.option.homeTeam} - ${item.option.awayTeam}"
            )
        }
        SofascoreNotificationListenerService.refreshIfConnected()
        showHomeState()
    }

    /**
     * Bouton "API" d'une ligne de l'accueil Sport. Si un override est déjà
     * configuré pour cette notification, propose de le changer ou de le
     * retirer plutôt que de rouvrir directement la recherche — pour ne pas
     * perdre l'override actuel par mégarde sur un tap accidentel.
     */
    private fun openApiPickerFor(option: SofascoreMatchOption) {
        val existing = option.override
        if (existing == null) {
            startApiPicker(option.key)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("${option.homeTeam} - ${option.awayTeam}")
            .setItems(arrayOf("Changer le match suivi", "Retirer l'API (${existing.label})")) { _, which ->
                if (which == 0) startApiPicker(option.key) else removeOverride(option.key)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun removeOverride(notifKey: String) {
        SofascoreApiOverridePrefs.remove(this, notifKey)
        SofascoreNotificationListenerService.refreshIfConnected()
        Toast.makeText(this, "API retirée", Toast.LENGTH_SHORT).show()
        showHomeState()
    }

    private fun startApiPicker(notifKey: String) {
        pendingOverrideNotifKey = notifKey
        showSearchState()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun runSearch() {
        val query = binding.editSearch.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return

        binding.textStatus.text = "Recherche de \"$query\"…"

        when (apiMode) {
            ApiMode.LIVE_TENNIS -> searchTennisPlayers(query)
            ApiMode.SPORTS_DB -> when (searchMode) {
                SearchMode.TEAM -> searchTeams(query)
                SearchMode.PLAYER -> searchPlayers(query)
                SearchMode.LEAGUE -> searchLeagues(query)
            }
        }
    }

    /** Tennis : la recherche se fait uniquement par nom de joueur (pas d'équipe/ligue au sens de TheSportsDB). */
    private fun searchTennisPlayers(query: String) {
        val apiKey = TennisApiKeyPrefs.get(this)
        if (apiKey == null) {
            binding.textStatus.text = "Renseigne ta clé Live Tennis API ci-dessus avant de chercher"
            return
        }
        lifecycleScope.launch {
            val players = safeCall { LiveTennisApi.searchPlayers(query, apiKey) }
            if (players.isEmpty()) {
                binding.textStatus.text = "Aucun joueur trouvé pour \"$query\""
                binding.recyclerView.adapter = null
                return@launch
            }
            binding.textStatus.text = "${players.size} joueur(s) trouvé(s) — choisis-en un"
            binding.recyclerView.adapter = SimpleListAdapter(players, ::tennisPlayerLabel) { player ->
                showTennisMatchesForPlayerToday(player.id, player.name, apiKey)
            }
        }
    }

    private fun tennisPlayerLabel(player: TennisPlayerResult): String {
        val ranking = player.ranking?.let { " · #$it" } ?: ""
        val tour = player.tour?.let { " · $it" } ?: ""
        return "${player.name}$ranking$tour"
    }

    private fun showTennisMatchesForPlayerToday(playerId: Int, label: String, apiKey: String) {
        binding.textStatus.text = "Chargement des matchs de $label…"
        lifecycleScope.launch {
            val matches = safeCall { LiveTennisApi.getMatchesForPlayerToday(playerId, apiKey) }
            showMatchResults(matches, label)
        }
    }

    private fun searchTeams(query: String) {
        lifecycleScope.launch {
            val teams = safeCall { SportsDbApi.searchTeams(query, sportsDbSport.apiValue) }
            if (teams.isEmpty()) {
                binding.textStatus.text = "Aucune équipe trouvée pour \"$query\"${sportSuffix()}"
                binding.recyclerView.adapter = null
                return@launch
            }
            binding.textStatus.text = "${teams.size} équipe(s) trouvée(s) — choisis-en une"
            binding.recyclerView.adapter = SimpleListAdapter(teams, { it.name }) { team ->
                showMatchesForTeamToday(team.id, team.name)
            }
        }
    }

    private fun searchPlayers(query: String) {
        lifecycleScope.launch {
            val players = safeCall { SportsDbApi.searchPlayers(query, sportsDbSport.apiValue) }
            if (players.isEmpty()) {
                binding.textStatus.text = "Aucun joueur trouvé pour \"$query\"${sportSuffix()}"
                binding.recyclerView.adapter = null
                return@launch
            }
            binding.textStatus.text = "${players.size} joueur(s) trouvé(s) — choisis-en un"
            binding.recyclerView.adapter = SimpleListAdapter(
                players,
                { "${it.name} (${it.teamName ?: "équipe inconnue"})" }
            ) { player ->
                val teamId = player.teamId
                if (teamId == null) {
                    Toast.makeText(this@MainActivity, "Équipe inconnue pour ce joueur", Toast.LENGTH_SHORT).show()
                } else {
                    showMatchesForTeamToday(teamId, player.teamName ?: player.name)
                }
            }
        }
    }

    private fun searchLeagues(query: String) {
        lifecycleScope.launch {
            val leagues = safeCall { SportsDbApi.searchLeagues(query, sportsDbSport.apiValue) }
            if (leagues.isEmpty()) {
                binding.textStatus.text = "Aucune ligue trouvée pour \"$query\"${sportSuffix()}"
                binding.recyclerView.adapter = null
                return@launch
            }
            binding.textStatus.text = "${leagues.size} ligue(s) trouvée(s) — choisis-en une"
            binding.recyclerView.adapter = SimpleListAdapter(leagues, { it.name }) { league ->
                showMatchesForLeagueToday(league.id, league.name)
            }
        }
    }

    /** "" si "Tous sports" (rien à préciser), sinon " (Football)" etc. — pour les messages de statut. */
    private fun sportSuffix(): String =
        if (sportsDbSport == SportsDbSport.ALL) "" else " (${sportsDbSport.label})"

    private fun showMatchesForTeamToday(teamId: String, label: String) {
        binding.textStatus.text = "Chargement des matchs de $label…"
        lifecycleScope.launch {
            // getMatchesForTeam renvoie les derniers/prochains matchs (pas
            // forcément aujourd'hui) — filtrage côté client nécessaire ici,
            // contrairement à showMatchesForLeagueToday où eventsday.php
            // filtre déjà par date côté serveur.
            val matches = safeCall { SportsDbApi.getMatchesForTeam(teamId) }
                .filter { SportsDbApi.isToday(it.date) }
            showMatchResults(matches, label)
        }
    }

    private fun showMatchesForLeagueToday(leagueId: String, label: String) {
        binding.textStatus.text = "Chargement des matchs de $label…"
        lifecycleScope.launch {
            val matches = safeCall { SportsDbApi.getMatchesForLeagueToday(leagueId) }
            showMatchResults(matches, label)
        }
    }

    private fun showMatchResults(matches: List<MatchResult>, label: String) {
        if (matches.isEmpty()) {
            binding.textStatus.text = "Aucun match aujourd'hui pour $label"
            binding.recyclerView.adapter = null
            return
        }
        binding.textStatus.text = "Matchs aujourd'hui — $label"
        binding.recyclerView.adapter = MatchesAdapter(matches) { match -> onMatchSelected(match) }
    }

    private suspend fun <T> safeCall(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: Exception) {
        Toast.makeText(this@MainActivity, "Erreur réseau", Toast.LENGTH_SHORT).show()
        emptyList()
    }

    /**
     * Un match a été choisi dans la recherche API — demande maintenant ce
     * qu'il faut en afficher (score et/ou période, voir
     * [showOverrideFieldsDialog]) pour la notification Sofascore visée par
     * [pendingOverrideNotifKey]. Ce flux ne s'ouvre plus que depuis
     * [openApiPickerFor] : le filet de sécurité ci-dessous (notifKey null)
     * ne devrait normalement jamais se déclencher.
     */
    private fun onMatchSelected(match: MatchResult) {
        val notifKey = pendingOverrideNotifKey
        if (notifKey == null) {
            Toast.makeText(this, "Sélectionne d'abord une notification Sofascore", Toast.LENGTH_SHORT).show()
            showHomeState()
            return
        }
        showOverrideFieldsDialog(notifKey, match)
    }

    /**
     * "Choix entre score et minute/période" — sélection multiple (case à
     * cocher), pas un choix exclusif : Yann peut vouloir l'un, l'autre, ou
     * les deux ("le score et/ou la période de l'API"). Refuse d'enregistrer
     * si aucune case n'est cochée (un override sans effet n'a pas de sens).
     */
    private fun showOverrideFieldsDialog(notifKey: String, match: MatchResult) {
        val fields = arrayOf("Score", "Minute / période")
        val checked = booleanArrayOf(true, true)
        AlertDialog.Builder(this)
            .setTitle("Qu'afficher pour ${match.homeTeam} - ${match.awayTeam} ?")
            .setMultiChoiceItems(fields, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("OK") { _, _ ->
                if (!checked[0] && !checked[1]) {
                    Toast.makeText(this, "Choisis au moins score ou période", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                SofascoreApiOverridePrefs.save(
                    this,
                    SofascoreApiOverride(
                        notifKey = notifKey,
                        source = match.source,
                        matchId = match.id,
                        label = match.title,
                        showScore = checked[0],
                        showPeriod = checked[1]
                    )
                )
                SofascoreNotificationListenerService.refreshIfConnected()
                Toast.makeText(this, "API liée à ce match", Toast.LENGTH_SHORT).show()
                showHomeState()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showSearchState() {
        binding.searchControls.visibility = View.VISIBLE
        binding.recyclerView.adapter = null
        binding.editSearch.setText("")

        when (apiMode) {
            ApiMode.SPORTS_DB -> {
                binding.radioSportsDbSport.visibility = View.VISIBLE
                binding.tennisApiKeyRow.visibility = View.GONE
                // Le sélecteur équipe/joueur/ligue n'a de sens qu'avec
                // TheSportsDB ; masqué en tennis (recherche par joueur
                // uniquement).
                binding.radioSearchMode.visibility = View.VISIBLE
                binding.editSearch.hint = when (searchMode) {
                    SearchMode.TEAM -> "Nom d'équipe (ex. PSG)"
                    SearchMode.PLAYER -> "Nom de joueur (ex. Mbappé)"
                    SearchMode.LEAGUE -> "Nom de ligue (ex. Ligue 1)"
                }
                binding.textStatus.text =
                    "Cherche une équipe, un joueur ou une ligue${sportSuffix()} pour commencer"
            }
            ApiMode.LIVE_TENNIS -> {
                binding.radioSportsDbSport.visibility = View.GONE
                binding.radioSearchMode.visibility = View.GONE
                binding.tennisApiKeyRow.visibility = View.VISIBLE
                // Pré-remplit avec la clé déjà enregistrée, si elle existe
                // (voir TennisApiKeyPrefs.kt), pour que Yann la voie/la
                // corrige sans avoir à la recoller depuis zéro.
                binding.editTennisApiKey.setText(TennisApiKeyPrefs.get(this) ?: "")
                binding.editSearch.hint = "Nom de joueur (ex. Alcaraz)"
                binding.textStatus.text = if (TennisApiKeyPrefs.get(this) == null) {
                    "Renseigne ta clé Live Tennis API ci-dessus pour commencer"
                } else {
                    "Cherche un joueur de tennis pour commencer"
                }
            }
        }
    }

    /**
     * Icône d'effacement (✕) dans le champ de recherche : détection de tap
     * explicite sur la zone du drawable — icône système
     * `ic_menu_close_clear_cancel`, pas de nouvelle ressource à ajouter —
     * qui vide le champ de façon certaine.
     */
    private fun setupSearchClearButton() {
        updateSearchClearIcon()
        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateSearchClearIcon()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        binding.editSearch.setOnTouchListener { _, event ->
            val drawableEnd = binding.editSearch.compoundDrawables[2]
            if (drawableEnd != null && event.action == MotionEvent.ACTION_UP) {
                val iconStart = binding.editSearch.width -
                    binding.editSearch.paddingEnd -
                    drawableEnd.bounds.width()
                if (event.x >= iconStart) {
                    binding.editSearch.setText("")
                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    private fun updateSearchClearIcon() {
        val icon = if (binding.editSearch.text.isNullOrEmpty()) 0 else android.R.drawable.ic_menu_close_clear_cancel
        binding.editSearch.setCompoundDrawablesWithIntrinsicBounds(0, 0, icon, 0)
    }
}
