package com.gustmmer.poker.bot

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.toCards
import com.gustmmer.poker.round.CommandType
import com.gustmmer.poker.round.PokerRoundStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random

class BotStrategyTest {

    private val sixHanded = listOf("2C 3D", "4H 5S", "6C 7D", "8H 9S", "JC QH", "10S 10D")

    private fun decide(view: BotView, personality: BotPersonality, rng: Random = FixedRoll.NEVER) =
        BotStrategy.decide(view, personality.profile, rng)

    private fun BotView.holding(cards: String) = copy(pocketCards = cards.toCards())

    private val aggressive = setOf(CommandType.RAISE, CommandType.ALL_IN)

    // ── Pre-flop ranges ─────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(BotPersonality::class)
    fun `every personality raises aces under the gun`(personality: BotPersonality) {
        val view = BotSpot(sixHanded).view().holding("AS AH") // seat 3 is first to act
        assertTrue(decide(view, personality).command.type in aggressive)
    }

    @ParameterizedTest
    @EnumSource(BotPersonality::class)
    fun `every personality folds seven-deuce to a raise`(personality: BotPersonality) {
        val view = BotSpot(sixHanded).raise(200).view().holding("7S 2D")
        assertEquals(CommandType.FOLD, decide(view, personality).command.type)
    }

    @Test
    fun `the aggressive bot steals the blinds from the button with a hand the defensive bot folds`() {
        val view = BotSpot(sixHanded).foldUntil(0).view().holding("KS 7D")
        assertEquals(Position.LATE, view.position)
        assertTrue(view.unopened)

        assertEquals(CommandType.RAISE, decide(view, BotPersonality.AGGRESSIVE).command.type)
        assertEquals(CommandType.FOLD, decide(view, BotPersonality.DEFENSIVE).command.type)
    }

    @Test
    fun `looser personalities play more starting hands`() {
        val view = BotSpot(sixHanded).fold().view() // seat 4: middle position, unopened
        assertEquals(Position.MIDDLE, view.position)

        fun handsPlayed(personality: BotPersonality) = PreflopChart.allClasses().count { hand ->
            decide(view.copy(pocketCards = representative(hand)), personality).command.type != CommandType.FOLD
        }

        val (agg, bal, def) = BotPersonality.entries.map(::handsPlayed)
        assertTrue(agg > bal && bal > def, "aggressive=$agg balanced=$bal defensive=$def")
    }

    @Test
    fun `the defensive bot limps more of its range than it raises`() {
        val view = BotSpot(sixHanded).foldUntil(0).view()
        val decisions = PreflopChart.allClasses().map { hand ->
            decide(view.copy(pocketCards = representative(hand)), BotPersonality.DEFENSIVE).command.type
        }
        assertTrue(decisions.count { it == CommandType.CALL } > decisions.count { it == CommandType.RAISE })
    }

    // ── Post-flop: reading opponents' ranges ────────────────────────────────────────────────────────

    @Test
    fun `middle pair calls a bet from a limper but folds the same bet from a pre-flop re-raiser`() {
        // Heads-up: seat 0 is the button (small blind), seat 1 the big blind — our bot, holding 8-8.
        val board = "KD 7C 2H 3S 4D"

        val limper = BotSpot(listOf("QS 9S", "8S 8D"), board = board, personality = BotPersonality.BALANCED)
            .call() // button limps
            .call() // bot checks its option
            .call() // bot checks the flop
            .raise(200) // button bets the pot
        val reRaiser = BotSpot(listOf("QS 9S", "8S 8D"), board = board, personality = BotPersonality.BALANCED)
            .call() // button limps
            .raise(200) // bot raises to 300
            .raise(600) // button re-raises to 900
            .call() // bot calls
            .call() // bot checks the flop
            .raise(1800) // button bets the pot

        assertEquals(PokerRoundStage.BET_FLOP, limper.view().stage)
        assertEquals(CommandType.CALL, decide(limper.view(), BotPersonality.BALANCED).command.type)
        // Same pot odds either way; only the range the bet comes from differs.
        assertEquals(CommandType.FOLD, decide(reRaiser.view(), BotPersonality.BALANCED).command.type)
    }

    @Test
    fun `bets a strong made hand for value when checked to`() {
        val spot = BotSpot(listOf("KS KH", "4H 5S", "9C 9D"), board = "KD 7C 2H 3S 4D").callAround().callUntil(0)
        val decision = decide(spot.view(), BotPersonality.BALANCED)
        assertTrue(decision.command.type in aggressive, decision.toString())
        assertEquals("value bet", decision.reason)
    }

    // ── Short stacks ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a short stack plays all-in or fold`() {
        val view = BotSpot(sixHanded, chips = 800).view() // 8 big blinds, under the gun
        assertEquals(CommandType.ALL_IN, decide(view.holding("AS 9D"), BotPersonality.BALANCED).command.type)
        assertEquals(CommandType.FOLD, decide(view.holding("7S 2D"), BotPersonality.BALANCED).command.type)
    }

    // ── Consistency and the ≤10% randomness budget ──────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(BotPersonality::class)
    fun `deviation rates stay within 10 percent`(personality: BotPersonality) {
        assertTrue(personality.profile.deviationRate <= 0.10)
        assertThrows<IllegalArgumentException> { personality.profile.copy(deviationRate = 0.11) }
    }

    @Test
    fun `the same spot always gets the same decision when no deviation fires`() {
        val view = BotSpot(sixHanded, board = "QS JH 4C 9D 2S").callAround().callUntil(4).view()
        val first = decide(view, BotPersonality.AGGRESSIVE)
        repeat(5) {
            val again = decide(view, BotPersonality.AGGRESSIVE)
            assertEquals(first.command.describe(), again.command.describe())
            assertEquals(first.thinkMs, again.thinkMs)
        }
    }

    @Test
    fun `the aggressive bot sometimes bluffs a pot checked to it in position`() {
        val view = BotSpot(listOf("7S 2D", "4H 5S", "9C 9D"), board = "KD QC 3H 8S JD").callAround().callUntil(0).view()
        assertEquals(CommandType.CALL, decide(view, BotPersonality.AGGRESSIVE, FixedRoll.NEVER).command.type, "checks by default")
        val bluff = decide(view, BotPersonality.AGGRESSIVE, FixedRoll.ALWAYS)
        assertEquals(CommandType.RAISE, bluff.command.type)
        assertEquals("bluff", bluff.reason)
    }

    @Test
    fun `no bluffing into a crowd - the roll isn't even made`() {
        val view = BotSpot(sixHanded + "7S 2H", board = "KD QC 3S 8C JD").callAround().callUntil(6).view()
        assertTrue(view.opponents.size > 2)
        val plain = decide(view, BotPersonality.AGGRESSIVE, FixedRoll.NEVER)
        val rolled = decide(view, BotPersonality.AGGRESSIVE, FixedRoll.ALWAYS)
        assertEquals(plain.command.describe(), rolled.command.describe())
    }

    @Test
    fun `the defensive bot sometimes slow-plays aces`() {
        val view = BotSpot(sixHanded).view().holding("AS AH")
        assertEquals(CommandType.RAISE, decide(view, BotPersonality.DEFENSIVE, FixedRoll.NEVER).command.type)
        val slowPlay = decide(view, BotPersonality.DEFENSIVE, FixedRoll.ALWAYS)
        assertEquals(CommandType.CALL, slowPlay.command.type)
        assertEquals("slow-play", slowPlay.reason)
    }

    @ParameterizedTest
    @EnumSource(BotPersonality::class)
    fun `randomness changes at most 10 percent of decisions`(personality: BotPersonality) {
        val rng = Random(2026)
        val deals = Random(1)
        var decisions = 0
        var changed = 0
        repeat(400) {
            val cards = Card.cards.shuffled(deals)
            val pockets = (0 until 6).map { seat -> cards.subList(seat * 2, seat * 2 + 2).joinToString(" ") }
            val board = cards.subList(12, 17).joinToString(" ")
            val spot = BotSpot(pockets, board = board, personality = personality)
            // One pre-flop decision per seat position, then one flop decision after a limped pot.
            val views = listOf(spot.view(), spot.callAround().view())
            views.forEach { view ->
                decisions++
                val plain = decide(view, personality, FixedRoll.NEVER).command.describe()
                if (decide(view, personality, rng).command.describe() != plain) changed++
            }
        }
        val share = changed.toDouble() / decisions
        assertTrue(share <= 0.10, "$personality changed $changed of $decisions decisions")
    }

    // ── Timing ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `snap folds are quick and all-ins take a while`() {
        val facingRaise = BotSpot(sixHanded).raise(200).view()
        val fold = decide(facingRaise.holding("7S 2D"), BotPersonality.BALANCED)
        val open = decide(BotSpot(sixHanded).view().holding("AS KS"), BotPersonality.BALANCED)
        val shove = decide(BotSpot(sixHanded, chips = 800).view().holding("AS AD"), BotPersonality.BALANCED)

        assertEquals(CommandType.FOLD, fold.command.type)
        assertEquals(CommandType.ALL_IN, shove.command.type)
        assertTrue(fold.thinkMs < open.thinkMs && open.thinkMs < shove.thinkMs, "$fold / $open / $shove")
    }

    private fun representative(hand: String): List<Card> {
        val hi = hand[0].toString().replace("T", "10")
        val lo = hand[1].toString().replace("T", "10")
        return "${hi}S $lo${if (hand.endsWith("s")) "S" else "H"}".toCards()
    }
}
