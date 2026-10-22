package io.github.konyaco.tukuai.smoke

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.RulesScreen
import io.github.konyaco.tukuai.client.TablePointer
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.content.BigCardTableBlock
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.SeatEntity
import io.github.konyaco.tukuai.content.StoolBlock
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.content.Wood
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.platform.Services
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import io.github.konyaco.tukuai.table.ServerSettings
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import java.util.function.Supplier
import kotlin.system.exitProcess
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.Difficulty
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.BlockItemStateProperties
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Fabric's client game tests only exist from Minecraft 1.21.4 on, so on 1.21.1 this plays
 * through the mod in a real client by itself: it makes a flat world, places and builds
 * tables, plays a game of each kind with computers at the table, and takes screenshots
 * along the way. Any failure stops the game with exit code 1. Each loader starts it from
 * a small test mod of its own: `./gradlew :fabric-1.21.1:runSmokeTest` or
 * `./gradlew :neoforge-1.21.1:runSmokeTest`.
 */
object SmokeTest {
    fun start() {
        Thread({
            val failed = try {
                run()
                false
            } catch (e: Throwable) {
                TuKuai.LOGGER.error("SMOKE FAILED", e)
                true
            }
            TuKuai.LOGGER.info(if (failed) "SMOKE FAILED" else "SMOKE PASSED")
            exitProcess(if (failed) 1 else 0)
        }, "TuKuai smoke test").apply { isDaemon = true }.start()
    }

    private val mc: Minecraft get() = Minecraft.getInstance()

    private fun run() {
        waitFor("the title screen", 6000) { mc.overlay == null && mc.screen is TitleScreen }
        onClient {
            val name = "tukuai-smoke"
            val settings = LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT)
            mc.createWorldOpenFlows().createFreshLevel(name, settings, WorldOptions(1L, false, false), { registries ->
                registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions()
            }, mc.screen!!)
        }
        waitFor("the world", 6000) { mc.level != null && mc.player != null && mc.screen == null }
        ticks(60)

        val (table, big) = onServer { buildScene(it) }
        ticks(40)
        look(-4.0, 3.0, table)
        ticks(20)
        shot("1-world-tables")
        check(onServer { s -> s.overworld().getBlockEntity(big) is CardTableBlockEntity }) { "The big table has no block entity" }

        // Fast computers, so a game is over in moments.
        onServer {
            val field = ServerSettings::class.java.getDeclaredField("botDelaySeconds")
            field.isAccessible = true
            field.set(null, 0.05)
        }

        act(table, TableOp.SIT, 0)
        ticks(10)
        check(onServer { s -> s.playerList.players.first().vehicle is SeatEntity }) { "Sitting down did not seat the player" }
        onClient { mc.player!!.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(table).add(0.0, -0.2, 0.0)) }
        act(table, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
        waitFor("the table", 200) { ClientTables[table] != null }
        onClient { mc.setScreen(TableScreen(table)) }
        ticks(20)
        shot("2-screen-lobby")
        onClient { RulesScreen.open(mc.screen, ClientTables[table], "doudizhu") }
        ticks(20)
        shot("3-screen-rules")
        onClient { mc.setScreen(TableScreen(table)) }
        ticks(10)

        for (game in listOf("doudizhu", "uno", "tutu", "paodekuai", "guandan")) play(table, game)

        // The hotbar shows the items in their woods and colours.
        onClient { mc.setScreen(null) }
        act(table, TableOp.STAND)
        ticks(20)
        look(0.0, -3.0, table)
        ticks(20)
        shot("9-world-hotbar")
    }

    /** Plays one game of [game] at [table] with computers, the player's seat on auto play. */
    private fun play(table: BlockPos, game: String) {
        act(table, TableOp.GAME, text = game)
        ticks(10)
        act(table, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
        ticks(10)
        act(table, TableOp.START)
        waitFor("$game to start", 400) { ClientTables[table]?.snapshot?.let { it.gameId == game && it.status == TableStatus.PLAYING } == true }
        ticks(30)
        shot("4-$game-screen")
        onClient {
            ClientConfig.immersive = true
            (mc.screen as? TableScreen)?.refresh()
        }
        ticks(20)
        shot("5-$game-immersive")
        check(onClient { cardsUnderMouse(table) }) { "No card of the player's could be pointed at on the table in $game" }
        onClient {
            ClientConfig.immersive = false
            (mc.screen as? TableScreen)?.refresh()
        }
        act(table, TableOp.AUTO, 1)
        waitFor("$game to finish", 20 * 60 * 3) { ClientTables[table]?.snapshot?.status == TableStatus.FINISHED }
        ticks(20)
        shot("6-$game-result")
        act(table, TableOp.AUTO, 0)
    }

    /**
     * Whether any of the player's cards on the table is under some spot of the screen, as
     * the table screen finds it for the mouse in the immersive view. This needs the view
     * matrices the table renderer keeps.
     */
    private fun cardsUnderMouse(table: BlockPos): Boolean {
        val width = mc.window.guiScaledWidth
        val height = mc.window.guiScaledHeight
        for (y in 0 until height step 4) for (x in 0 until width step 4) {
            if (TablePointer.cardAt(table, x.toDouble(), y.toDouble(), width, height) != null) return true
        }
        return false
    }

    /**
     * A floor of planks, a cherry table placed from its item with stools on three sides,
     * and a spruce big table behind it. The hotbar gets a few of the mod's items.
     */
    private fun buildScene(server: MinecraftServer): Pair<BlockPos, BlockPos> {
        val player = server.playerList.players.first()
        val level = server.overworld()
        level.setDayTime(6000)
        val base = player.blockPosition()
        for (x in -8..8) for (z in -12..8) {
            for (y in 0..4) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState())
            level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.OAK_PLANKS.defaultBlockState())
        }
        player.teleportTo(base.x + 0.5, base.y.toDouble(), base.z + 0.5)

        // Placed by a player looking north: the table reaches north from the clicked block.
        player.setYRot(180f)
        val clicked = base.north(2)
        val stack = tableItem(TuKuaiContent.CARD_TABLE_ITEM, Wood.CHERRY)
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        stack.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, BlockHitResult(Vec3.atCenterOf(clicked.below()).add(0.0, 0.5, 0.0), Direction.UP, clicked.below(), false)))
        val table = clicked.north()
        for (part in 0 until CardTableBlock.PARTS) {
            val state = level.getBlockState(CardTableBlock.posOf(table, part))
            check(state.block is CardTableBlock && state.getValue(CardTableBlock.PART) == part && state.getValue(CardTableBlock.WOOD) == Wood.CHERRY) {
                "Placing a table left part $part as $state"
            }
        }
        for ((side, color) in listOf(Direction.SOUTH to DyeColor.RED, Direction.EAST to DyeColor.YELLOW, Direction.WEST to DyeColor.LIGHT_BLUE)) {
            level.setBlockAndUpdate(
                table.relative(side, StoolBlock.TABLE_DISTANCE),
                TuKuaiContent.STOOL.defaultBlockState().setValue(StoolBlock.COLOR, color).setValue(StoolBlock.WOOD, Wood.CHERRY),
            )
        }

        // The big table: its middle block alone, which grows the other 24 parts on its first tick.
        val big = table.north(6)
        level.setBlockAndUpdate(big, TuKuaiContent.BIG_CARD_TABLE.defaultBlockState().setValue(CardTableBlock.FACING, Direction.SOUTH).setValue(CardTableBlock.WOOD, Wood.SPRUCE))
        for (along in listOf(-1, 1)) {
            level.setBlockAndUpdate(big.south(3).east(along), TuKuaiContent.STOOL.defaultBlockState().setValue(StoolBlock.WOOD, Wood.SPRUCE).setValue(StoolBlock.COLOR, DyeColor.GREEN))
        }
        check((level.getBlockState(big).block as? BigCardTableBlock)?.radius == 2) { "The big table is not a big table" }

        val hotbar = listOf(
            tableItem(TuKuaiContent.CARD_TABLE_ITEM, Wood.OAK),
            tableItem(TuKuaiContent.CARD_TABLE_ITEM, Wood.WARPED),
            tableItem(TuKuaiContent.BIG_CARD_TABLE_ITEM, Wood.BIRCH),
            stool(Wood.OAK, DyeColor.RED),
            stool(Wood.BIRCH, DyeColor.BLUE),
            stool(Wood.DARK_OAK, DyeColor.YELLOW),
            stool(Wood.CRIMSON, DyeColor.WHITE),
        )
        hotbar.forEachIndexed { i, item -> player.inventory.setItem(i, item) }
        return table to big
    }

    private fun tableItem(item: Item, wood: Wood): ItemStack =
        ItemStack(item).apply { set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(CardTableBlock.WOOD, wood)) }

    private fun stool(wood: Wood, color: DyeColor): ItemStack = ItemStack(TuKuaiContent.STOOL_ITEM).apply {
        set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(StoolBlock.WOOD, wood).with(StoolBlock.COLOR, color))
    }

    /** Stands the player ([dx], [dz]) from [target] and turns them to look at it. */
    private fun look(dx: Double, dz: Double, target: BlockPos) {
        onServer { s -> s.playerList.players.first().teleportTo(target.x + 0.5 + dx, target.y.toDouble(), target.z + 0.5 + dz) }
        ticks(5)
        onClient {
            val player = mc.player!!
            player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(target).add(0.0, 0.4, 0.0))
        }
    }

    /** What a player at the table sends, through the network as from the table screen. */
    private fun act(pos: BlockPos, op: TableOp, a: Int = 0, b: Int = 0, text: String = "") {
        onClient { Services.client.sendToServer(TableActionPayload(pos, op, a, b, text)) }
        ticks(2)
    }

    private fun shot(name: String) {
        onClient {
            Screenshot.grab(mc.gameDirectory, "tukuai-$name.png", mc.mainRenderTarget) { message ->
                TuKuai.LOGGER.info("SCREENSHOT {}: {}", name, message.string)
            }
        }
        ticks(2)
    }

    private fun <T> onClient(action: () -> T): T = mc.submit(Supplier { action() }).get()

    private fun <T> onServer(action: (MinecraftServer) -> T): T {
        val server = mc.singleplayerServer ?: error("No integrated server")
        return server.submit(Supplier { action(server) }).get()
    }

    private fun ticks(count: Int) {
        val until = onClient { mc.level?.gameTime ?: 0L } + count
        waitFor("$count ticks", count + 200) { (mc.level?.gameTime ?: until) >= until }
    }

    /** Waits for [condition], checked on the client thread, for up to about [ticks] ticks. */
    private fun waitFor(what: String, ticks: Int, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ticks * 50L + 5_000
        while (!onClient(condition)) {
            check(System.currentTimeMillis() < deadline) { "Gave up waiting for $what" }
            Thread.sleep(50)
        }
    }
}
