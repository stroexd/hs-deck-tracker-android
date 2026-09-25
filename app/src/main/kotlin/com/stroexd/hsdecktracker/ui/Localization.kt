package com.stroexd.hsdecktracker.ui

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.core.cards.CardType
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.cards.Ownership
import com.stroexd.hsdecktracker.core.cards.Rarity
import com.stroexd.hsdecktracker.core.collection.CollectionImportException
import com.stroexd.hsdecktracker.core.data.LoadError
import com.stroexd.hsdecktracker.core.data.MetaSourceType
import com.stroexd.hsdecktracker.core.data.RankRange
import com.stroexd.hsdecktracker.core.data.TimeRange
import com.stroexd.hsdecktracker.core.deck.DeckIssue
import com.stroexd.hsdecktracker.core.stats.MatchResult
import com.stroexd.hsdecktracker.core.stats.MatchSource
import com.stroexd.hsdecktracker.core.stats.ResultFilter
import com.stroexd.hsdecktracker.core.vision.VisionGameTracker
import java.util.Locale

/** The app follows the Hearthstone client language instead of the device language. */
fun Context.withLocale(locale: Locale): Context {
    val config = Configuration(resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    // A wrapper (instead of createConfigurationContext) keeps the activity reachable for launchers
    return ContextThemeWrapper(this, R.style.Theme_HsDeckTracker).apply { applyOverrideConfiguration(config) }
}

fun Context.localized(): Context = withLocale(appContainer.appLocale.value)

@Composable
fun ProvideAppLocale(locale: Locale, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val localized = remember(base, locale) { base.withLocale(locale) }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        content = content,
    )
}

@StringRes
fun HsClass.labelRes(): Int = when (this) {
    HsClass.DEATHKNIGHT -> R.string.class_death_knight
    HsClass.DEMONHUNTER -> R.string.class_demon_hunter
    HsClass.DRUID -> R.string.class_druid
    HsClass.HUNTER -> R.string.class_hunter
    HsClass.MAGE -> R.string.class_mage
    HsClass.PALADIN -> R.string.class_paladin
    HsClass.PRIEST -> R.string.class_priest
    HsClass.ROGUE -> R.string.class_rogue
    HsClass.SHAMAN -> R.string.class_shaman
    HsClass.WARLOCK -> R.string.class_warlock
    HsClass.WARRIOR -> R.string.class_warrior
    HsClass.NEUTRAL -> R.string.class_neutral
    HsClass.UNKNOWN -> R.string.class_unknown
}

@StringRes
fun Rarity.labelRes(): Int = when (this) {
    Rarity.FREE -> R.string.rarity_free
    Rarity.COMMON -> R.string.rarity_common
    Rarity.RARE -> R.string.rarity_rare
    Rarity.EPIC -> R.string.rarity_epic
    Rarity.LEGENDARY -> R.string.rarity_legendary
    Rarity.UNKNOWN -> R.string.rarity_unknown
}

@StringRes
fun CardType.labelRes(): Int = when (this) {
    CardType.MINION -> R.string.type_minion
    CardType.SPELL -> R.string.type_spell
    CardType.WEAPON -> R.string.type_weapon
    CardType.HERO -> R.string.type_hero
    CardType.LOCATION -> R.string.type_location
    CardType.HERO_POWER -> R.string.type_hero_power
    CardType.ENCHANTMENT -> R.string.type_enchantment
    CardType.UNKNOWN -> R.string.type_unknown
}

@StringRes
fun GameFormat.labelRes(): Int = when (this) {
    GameFormat.WILD -> R.string.format_wild
    GameFormat.STANDARD -> R.string.format_standard
    GameFormat.CLASSIC -> R.string.format_classic
    GameFormat.TWIST -> R.string.format_twist
}

@StringRes
fun MatchResult.labelRes(): Int = when (this) {
    MatchResult.WIN -> R.string.result_win
    MatchResult.LOSS -> R.string.result_loss
    MatchResult.DRAW -> R.string.result_draw
}

@StringRes
fun MatchSource.labelRes(): Int = when (this) {
    MatchSource.MANUAL -> R.string.source_manual
    MatchSource.TRACKER -> R.string.source_tracker
    MatchSource.AUTO -> R.string.source_auto
}

@StringRes
fun Ownership.labelRes(): Int = when (this) {
    Ownership.ALL -> R.string.ownership_all
    Ownership.OWNED -> R.string.ownership_owned
    Ownership.MISSING -> R.string.ownership_missing
    Ownership.INCOMPLETE -> R.string.ownership_incomplete
}

@StringRes
fun ResultFilter.labelRes(): Int = when (this) {
    ResultFilter.ALL -> R.string.results_all
    ResultFilter.WINS -> R.string.results_wins
    ResultFilter.LOSSES -> R.string.results_losses
}

@StringRes
fun MetaSourceType.labelRes(): Int = when (this) {
    MetaSourceType.HSREPLAY -> R.string.meta_source_hsreplay
    MetaSourceType.CUSTOM_URL -> R.string.meta_source_url
}

@StringRes
fun RankRange.labelRes(): Int = when (this) {
    RankRange.BRONZE_THROUGH_GOLD -> R.string.rank_bronze_gold
    RankRange.DIAMOND_THROUGH_LEGEND -> R.string.rank_diamond_legend
    RankRange.LEGEND_ONLY -> R.string.rank_legend
    RankRange.ALL -> R.string.rank_all
}

@StringRes
fun TimeRange.labelRes(): Int = when (this) {
    TimeRange.CURRENT_PATCH -> R.string.time_current_patch
    TimeRange.LAST_3_DAYS -> R.string.time_last_3_days
    TimeRange.LAST_7_DAYS -> R.string.time_last_7_days
    TimeRange.LAST_14_DAYS -> R.string.time_last_14_days
}

@StringRes
fun VisionGameTracker.Phase.labelRes(): Int = when (this) {
    VisionGameTracker.Phase.IDLE -> R.string.phase_idle
    VisionGameTracker.Phase.MULLIGAN -> R.string.phase_mulligan
    VisionGameTracker.Phase.PLAYING -> R.string.phase_playing
    VisionGameTracker.Phase.ENDED -> R.string.phase_ended
}

@Composable
fun HsClass.label(): String = stringResource(labelRes())

@Composable
fun Rarity.label(): String = stringResource(labelRes())

@Composable
fun CardType.label(): String = stringResource(labelRes())

@Composable
fun GameFormat.label(): String = stringResource(labelRes())

@Composable
fun MatchResult.label(): String = stringResource(labelRes())

fun LoadError.message(context: Context): String = when (this) {
    is LoadError.Forbidden -> context.getString(R.string.error_forbidden, code)
    is LoadError.Http -> context.getString(R.string.error_http, code)
    is LoadError.Network -> context.getString(R.string.error_network, detail)
    LoadError.NoData -> context.getString(R.string.error_no_data)
    LoadError.InvalidUrl -> context.getString(R.string.error_invalid_url)
    LoadError.NoDecksAtUrl -> context.getString(R.string.error_no_decks_at_url)
}

@Composable
fun LoadError.message(): String = message(LocalContext.current)

@Composable
fun DeckIssue.message(): String = when (this) {
    is DeckIssue.WrongSize -> stringResource(R.string.issue_wrong_size, count, expected)
    is DeckIssue.UnknownCard -> stringResource(R.string.issue_unknown_card, dbfId)
    is DeckIssue.TooManyCopies -> stringResource(R.string.issue_too_many, card.name, card.maxCopies)
    is DeckIssue.OtherClass -> stringResource(R.string.issue_other_class, card.name, deckClass.label())
    is DeckIssue.NotInFormat -> stringResource(R.string.issue_not_in_format, card.name, format.label())
}

fun CollectionImportException.message(context: Context): String = when (reason) {
    CollectionImportException.Reason.EMPTY -> context.getString(R.string.import_empty)
    CollectionImportException.Reason.INVALID_JSON -> context.getString(R.string.import_invalid_json)
    CollectionImportException.Reason.UNKNOWN_FORMAT -> context.getString(R.string.import_unknown_format)
    CollectionImportException.Reason.NO_CARDS ->
        if (unresolved.isEmpty()) {
            context.getString(R.string.import_no_cards)
        } else {
            context.getString(R.string.import_no_cards_unknown, unresolved.take(5).joinToString())
        }
}

fun defaultDeckName(context: Context): (HsClass) -> String = { cls ->
    context.getString(R.string.default_deck_name, context.getString(cls.labelRes()))
}

/** Localized words a match can be found by (class and result names). */
fun matchSearchLabels(context: Context): (com.stroexd.hsdecktracker.core.stats.MatchRecord) -> String = { m ->
    listOf(m.playerClass.labelRes(), m.opponentClass.labelRes(), m.result.labelRes()).joinToString(" ") { context.getString(it) }
}
