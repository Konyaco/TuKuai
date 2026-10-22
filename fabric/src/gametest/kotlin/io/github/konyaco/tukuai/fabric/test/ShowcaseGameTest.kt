package io.github.konyaco.tukuai.fabric.test

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.ReplayScreen
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.StoolBlock
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.doudizhu.DdzPhase
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.engine.guandan.GdPhase
import io.github.konyaco.tukuai.engine.guandan.GdView
import io.github.konyaco.tukuai.engine.paodekuai.PdkView
import io.github.konyaco.tukuai.engine.tutu.TutuView
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.core.BlockPos
import net.minecraft.client.gui.screens.inventory.CraftingScreen
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.entity.npc.villager.VillagerType
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.CraftingMenu
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import org.lwjgl.glfw.GLFW

/**
 * Takes the screenshots for the README. Only runs when `TUKUAI_SHOWCASE` lists the
 * languages to take them in, e.g. `TUKUAI_SHOWCASE=en_us,zh_cn ./gradlew :fabric:runClientGameTest`.
 * The pictures land in `fabric/build/run/clientGameTest/screenshots`.
 *
 * Two tables in a small card room: Dou Dizhu on the left with stools, UNO on the right,
 * where the villagers have taken the seats. Guandan and Run Fast are played for a while at
 * the Dou Dizhu table, between its replay and the walk over to UNO, and Tutu Cards at the
 * UNO table after UNO. The first language also takes the crafting recipes, which have no
 * words in them once cut down.
 */
class ShowcaseGameTest : FabricClientGameTest {
    private companion object {
        /** Table centers as (x, z) from where the player starts. */
        val DDZ = -4 to -4
        val UNO = 4 to -4
        const val WALL_Z = -8
        const val FRONT_Z = 3

        /** The floor reaches this far to either side; the side walls stand just past it. */
        const val ROOM_X = 9
    }

    override fun runTest(context: ClientGameTestContext) {
        val languages = System.getenv("TUKUAI_SHOWCASE")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return
        context.input.resizeWindow(1280, 720)
        for (lang in languages) {
            setLanguage(context, lang)
            context.worldBuilder().create().use { world -> shoot(context, world, lang, crafting = lang == languages.first()) }
        }
        context.runOnClient<RuntimeException> { it.options.hideGui = false }
        setLanguage(context, "en_us")
    }

    private fun setLanguage(context: ClientGameTestContext, code: String) {
        context.runOnClient<RuntimeException> { mc ->
            mc.options.languageCode = code
            mc.languageManager.setSelected(code)
            mc.reloadResourcePacks()
        }
        context.waitTicks(2)
        context.waitFor({ it.overlay == null }, 1200)
        context.waitTicks(10)
    }

    private fun shoot(context: ClientGameTestContext, world: TestSingleplayerContext, lang: String, crafting: Boolean) {
        val server = world.server
        val (ddz, uno) = server.computeOnServer<Pair<BlockPos, BlockPos>, RuntimeException> { s -> buildRoom(s) }
        command(server, "time set 6000")
        command(server, "weather clear")
        context.runOnClient<RuntimeException> {
            it.options.hideGui = true
            ClientConfig.immersive = false
        }
        context.waitTicks(40)
        world.clientLevel.waitForChunksRender()
        fun shot(name: String) {
            val path = context.takeScreenshot(TestScreenshotOptions.of("showcase-$lang-$name").disableCounterPrefix())
            TuKuai.LOGGER.info("SCREENSHOT {}", path.toAbsolutePath())
        }

        // Dou Dizhu: sit down, fill up with computers, play a while, then look around.
        act(server, ddz, TableOp.SIT, 0)
        act(server, ddz, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
        act(server, ddz, TableOp.START)
        context.waitFor({ ClientTables[ddz]?.snapshot?.status == TableStatus.PLAYING }, 200)
        context.setScreen { TableScreen(ddz) }
        act(server, ddz, TableOp.AUTO, 1)
        context.waitTicks(260)
        act(server, ddz, TableOp.AUTO, 0)
        playUntilBeating(context, server, ddz) { myDdzTurn(ddz)?.let { it.toBeat != null } }
        shot("ddz-table")
        // Seated players sit close to the table; a wider view takes in the others too.
        setFov(context, 85)
        context.input.lookAt(180f, 18f)
        setImmersive(context, true)
        context.waitTicks(5)
        shot("ddz-immersive")
        setImmersive(context, false)
        context.setScreen { null }
        context.waitTicks(10)
        shot("seated")

        act(server, ddz, TableOp.AUTO, 1)
        waitFinished(context, server, ddz)
        context.waitTicks(20)
        context.setScreen { TableScreen(ddz) }
        context.waitTicks(10)
        shot("result")
        context.waitFor({ ClientTables[ddz]?.snapshot?.seats?.firstOrNull()?.games == 1 }, 100)
        context.runOnClient<RuntimeException> { (it.screen as TableScreen).openLobby() }
        context.input.setCursorPos(0.0, 0.0)
        context.waitTicks(5)
        shot("lobby")
        context.setScreen { null }
        act(server, ddz, TableOp.REPLAY)
        context.waitFor({ it.screen is ReplayScreen }, 100)
        context.runOnClient<RuntimeException> {
            val replay = it.screen as ReplayScreen
            replay.pause()
            replay.seek(replay.total / 2)
        }
        context.waitTicks(5)
        shot("replay")
        context.setScreen { null }

        // Guandan at the same table, with a fourth computer, until we have a play to beat.
        act(server, ddz, TableOp.GAME, text = "guandan")
        act(server, ddz, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
        context.waitFor({ ClientTables[ddz]?.snapshot?.let { it.gameId == "guandan" && it.seats.size == 4 && it.seats.all { s -> s.occupant != null } } == true }, 100)
        act(server, ddz, TableOp.START)
        context.waitFor({ ClientTables[ddz]?.snapshot?.let { it.gameId == "guandan" && it.status == TableStatus.PLAYING } == true }, 200)
        context.setScreen { TableScreen(ddz) }
        act(server, ddz, TableOp.AUTO, 1)
        context.waitTicks(200)
        act(server, ddz, TableOp.AUTO, 0)
        playUntilBeating(context, server, ddz) { myGdTurn(ddz)?.let { it.toBeat != null } }
        shot("gd-table")
        context.setScreen { null }
        // Any one player may end the match.
        act(server, ddz, TableOp.END)
        context.waitFor({ ClientTables[ddz]?.snapshot?.status != TableStatus.PLAYING }, 100)

        // Run Fast, back to three seats, until we have a play to beat; then back to Dou Dizhu.
        act(server, ddz, TableOp.GAME, text = "paodekuai")
        context.waitFor({ ClientTables[ddz]?.snapshot?.let { it.gameId == "paodekuai" && it.seats.size == 3 && it.seats.all { s -> s.occupant != null } } == true }, 100)
        act(server, ddz, TableOp.START)
        context.waitFor({ ClientTables[ddz]?.snapshot?.let { it.gameId == "paodekuai" && it.status == TableStatus.PLAYING } == true }, 200)
        context.setScreen { TableScreen(ddz) }
        act(server, ddz, TableOp.AUTO, 1)
        context.waitTicks(160)
        act(server, ddz, TableOp.AUTO, 0)
        playUntilBeating(context, server, ddz) { myPdkTurn(ddz)?.let { it.toBeat != null && it.turnRules()?.plays?.isNotEmpty() == true } }
        shot("pdk-table")
        context.setScreen { null }
        act(server, ddz, TableOp.END)
        context.waitFor({ ClientTables[ddz]?.snapshot?.status != TableStatus.PLAYING }, 100)
        act(server, ddz, TableOp.GAME, text = "doudizhu")
        context.waitFor({ ClientTables[ddz]?.snapshot?.gameId == "doudizhu" }, 100)

        // UNO at the other table. The player has to stand up and walk over first.
        act(server, ddz, TableOp.STAND)
        teleport(server, 0.5, 0.0, 0.5, uno)
        act(server, uno, TableOp.GAME, text = "uno")
        act(server, uno, TableOp.SIT, 0)
        act(server, uno, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
        context.waitFor({ ClientTables[uno]?.snapshot?.let { it.gameId == "uno" && it.seats.all { s -> s.occupant != null } } == true }, 100)
        act(server, uno, TableOp.START)
        context.waitFor({ ClientTables[uno]?.snapshot?.let { it.gameId == "uno" && it.status == TableStatus.PLAYING } == true }, 200)
        context.setScreen { TableScreen(uno) }
        act(server, uno, TableOp.AUTO, 1)
        context.waitTicks(160)
        act(server, uno, TableOp.AUTO, 0)
        context.waitTicks(40)
        shot("uno-table")
        context.setScreen { null }
        context.input.lookAt(180f, 8f)
        context.waitTicks(10)
        shot("uno-seated")

        // Tutu Cards at the UNO table, with the party deck, until a TNT is burning on it.
        act(server, uno, TableOp.END)
        context.waitFor({ ClientTables[uno]?.snapshot?.status != TableStatus.PLAYING }, 100)
        act(server, uno, TableOp.GAME, text = "tutu")
        act(server, uno, TableOp.PRESET, text = "party")
        act(server, uno, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
        context.waitFor({ ClientTables[uno]?.snapshot?.let { it.gameId == "tutu" && it.seats.size == 4 && it.seats.all { s -> s.occupant != null } } == true }, 100)
        context.setScreen { TableScreen(uno) }
        // The computer plays for us until it is our turn with TNT burning; it waits a moment
        // before it plays, long enough to take back control.
        fun burning() = (ClientTables[uno]?.view as? TutuView)?.let { it.isMyTurn && it.bombs > 0 } == true
        hands@ for (hand in 0 until 10) {
            act(server, uno, TableOp.START)
            context.waitFor({ (ClientTables[uno]?.view as? TutuView)?.finished == false }, 200)
            while (true) {
                act(server, uno, TableOp.AUTO, 1)
                context.waitFor({ (ClientTables[uno]?.view as? TutuView)?.finished == true || burning() }, 6000)
                if (context.computeOnClient<Boolean, RuntimeException> { (ClientTables[uno]?.view as? TutuView)?.finished == true }) break
                act(server, uno, TableOp.AUTO, 0)
                context.waitTicks(5)
                if (context.computeOnClient<Boolean, RuntimeException> { burning() }) break@hands
            }
        }
        act(server, uno, TableOp.AUTO, 0)
        context.input.setCursorPos(0.0, 0.0)
        context.waitTicks(10)
        shot("tutu-table")
        setImmersive(context, true)
        context.input.lookAt(180f, 30f)
        context.waitTicks(10)
        shot("tutu-immersive")
        setImmersive(context, false)
        context.setScreen { null }
        context.waitTicks(10)
        shot("tutu-seated")
        // Back to UNO for the wide shots.
        act(server, uno, TableOp.END)
        context.waitFor({ ClientTables[uno]?.snapshot?.status != TableStatus.PLAYING }, 100)
        act(server, uno, TableOp.GAME, text = "uno")
        context.waitFor({ ClientTables[uno]?.snapshot?.let { it.gameId == "uno" && it.seats.all { s -> s.occupant != null } } == true }, 100)
        act(server, uno, TableOp.START)
        context.waitFor({ ClientTables[uno]?.snapshot?.let { it.gameId == "uno" && it.status == TableStatus.PLAYING } == true }, 200)

        // Leave both tables mid-game, where they wait for us, and take the wide shots.
        act(server, uno, TableOp.AUTO, 1)
        act(server, uno, TableOp.STAND)
        teleport(server, 0.5, 0.0, 0.5, ddz)
        server.runOnServer<RuntimeException> { s -> villager(s.overworld(), uno, Direction.SOUTH, VillagerProfession.CARTOGRAPHER, VillagerType.PLAINS) }
        if (status(server, ddz) != TableStatus.PLAYING) {
            act(server, ddz, TableOp.SIT, 0)
            act(server, ddz, TableOp.START)
            act(server, ddz, TableOp.AUTO, 1)
            act(server, ddz, TableOp.STAND)
        }
        setFov(context, 70)
        command(server, "gamemode spectator @a")
        val heroes = listOf(
            "hero" to doubleArrayOf(0.5, 5.0, 1.5, 0.5, 0.3, -4.5),
            "hero-high" to doubleArrayOf(0.5, 7.5, 2.0, 0.5, 0.0, -4.0),
            "hero-ddz" to doubleArrayOf(-7.5, 3.0, 0.5, -3.5, 0.8, -3.5),
            "hero-uno" to doubleArrayOf(8.5, 2.8, 0.5, 4.5, 0.8, -3.5),
        )
        for ((name, at) in heroes) {
            teleport(server, at[0], at[1], at[2], null, at[3], at[4], at[5])
            context.waitTicks(30)
            world.clientLevel.waitForChunksRender()
            shot(name)
        }
        command(server, "gamemode creative @a")
        if (crafting) craftingShots(context, server, ::shot)
    }

    /**
     * The crafting grid with each recipe laid out in it, at a large GUI scale so it can be
     * cut down to the grid and the result. Each is crafted with oak, and the table with
     * green wool to match the cloth it comes with.
     */
    private fun craftingShots(context: ClientGameTestContext, server: TestServerContext, shot: (String) -> Unit) {
        val wool = Items.GREEN_WOOL
        val planks = Items.OAK_PLANKS
        val log = Items.OAK_LOG
        val recipes = listOf(
            "card-table" to listOf(wool, wool, wool, planks, planks, planks, log, Items.AIR, log),
            "big-card-table" to listOf(wool, wool, wool, planks, TuKuaiContent.CARD_TABLE_ITEM, planks, log, Items.AIR, log),
            "stool" to listOf(Items.AIR, Items.RED_WOOL, Items.AIR, Items.AIR, Items.OAK_SLAB, Items.AIR, Items.AIR, Items.STICK, Items.AIR),
        )
        teleport(server, 0.5, 0.0, 0.5, null, 0.5, 1.0, -2.5)
        val bench = server.computeOnServer<BlockPos, RuntimeException> { s ->
            start!!.offset(0, 0, 2).also { s.overworld().setBlockAndUpdate(it, Blocks.CRAFTING_TABLE.defaultBlockState()) }
        }
        context.runOnClient<RuntimeException> {
            it.options.guiScale().set(4)
            it.resizeGui()
        }
        for ((name, grid) in recipes) {
            server.runOnServer<RuntimeException> { s -> craft(s, bench, grid) }
            context.waitFor({ (it.screen as? CraftingScreen)?.menu?.getSlot(0)?.hasItem() == true }, 100)
            context.input.setCursorPos(0.0, 0.0)
            context.waitTicks(10)
            shot("craft-$name")
        }
        server.runOnServer<RuntimeException> { s ->
            val player = s.playerList.players.first()
            player.closeContainer()
            player.inventory.clearContent()
        }
        context.runOnClient<RuntimeException> {
            it.options.guiScale().set(0)
            it.resizeGui()
        }
    }

    /** Opens a crafting table at [bench] for the player, with [grid] laid out in it row by row. */
    private fun craft(server: MinecraftServer, bench: BlockPos, grid: List<Item>) {
        val player = server.playerList.players.first()
        if (player.containerMenu !is CraftingMenu) {
            val access = ContainerLevelAccess.create(server.overworld(), bench)
            player.openMenu(SimpleMenuProvider({ id, inventory, _ -> CraftingMenu(id, inventory, access) }, Component.translatable("container.crafting")))
        }
        val menu = player.containerMenu as CraftingMenu
        grid.forEachIndexed { i, item -> menu.getSlot(1 + i).set(ItemStack(item)) }
        menu.broadcastChanges()
    }

    /**
     * Plays our own turns with the hint until someone else's cards are on the table, then
     * picks the hint's answer to them. A round that ends first is started again.
     * [beating] is null while it is not our turn.
     */
    private fun playUntilBeating(context: ClientGameTestContext, server: TestServerContext, table: BlockPos, beating: () -> Boolean?) {
        context.input.setCursorPos(0.0, 0.0)
        for (turn in 0 until 40) {
            context.waitFor({ beating() != null || ClientTables[table]?.snapshot?.status == TableStatus.FINISHED }, 1200)
            if (context.computeOnClient<Boolean?, RuntimeException> { beating() } == null) {
                act(server, table, TableOp.START)
                context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.PLAYING }, 200)
                continue
            }
            context.waitTicks(10)
            context.input.pressKey(GLFW.GLFW_KEY_H)
            context.waitTicks(5)
            if (context.computeOnClient<Boolean, RuntimeException> { beating() == true }) break
            context.input.pressKey(GLFW.GLFW_KEY_SPACE)
            context.waitTicks(5)
        }
        context.waitTicks(10)
    }

    private fun myPdkTurn(table: BlockPos): PdkView? =
        (ClientTables[table]?.view as? PdkView)?.takeIf { it.isMyTurn }

    private fun myGdTurn(table: BlockPos): GdView? =
        (ClientTables[table]?.view as? GdView)?.takeIf { it.isMyTurn && it.phase == GdPhase.PLAYING }

    private fun myDdzTurn(table: BlockPos): DdzView? =
        (ClientTables[table]?.view as? DdzView)?.takeIf { it.isMyTurn && it.phase == DdzPhase.PLAYING }

    private fun setFov(context: ClientGameTestContext, fov: Int) {
        context.runOnClient<RuntimeException> { it.options.fov().set(fov) }
    }

    private fun setImmersive(context: ClientGameTestContext, on: Boolean) {
        context.runOnClient<RuntimeException> {
            ClientConfig.immersive = on
            (it.screen as? TableScreen)?.refresh()
        }
    }

    /**
     * A card room around where the player stands: a plank floor, a wall of books to the
     * north, two tables, stools at the Dou Dizhu one and villagers at the UNO one.
     */
    private fun buildRoom(server: MinecraftServer): Pair<BlockPos, BlockPos> {
        val player = server.playerList.players.first()
        val level = server.overworld()
        val base = player.blockPosition()
        start = base
        for (x in -11..11) for (z in -11..5) {
            for (y in 0..7) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState())
            val edge = x == -ROOM_X || x == ROOM_X || z == WALL_Z + 1 || z == FRONT_Z
            val floor = when {
                x < -ROOM_X || x > ROOM_X || z <= WALL_Z || z > FRONT_Z -> Blocks.GRASS_BLOCK
                edge -> Blocks.DARK_OAK_PLANKS
                else -> Blocks.SPRUCE_PLANKS
            }
            level.setBlockAndUpdate(base.offset(x, -1, z), floor.defaultBlockState())
        }
        // Bookshelves between spruce posts, with a beam on top: along the back and partway down both sides.
        for (x in -ROOM_X - 1..ROOM_X + 1) wall(level, base.offset(x, 0, WALL_Z), x % 4 == 0 || x == -ROOM_X - 1 || x == ROOM_X + 1, Direction.Axis.X)
        for (side in listOf(-ROOM_X - 1, ROOM_X + 1)) {
            for (z in WALL_Z + 1..WALL_Z + 6) wall(level, base.offset(side, 0, z), z == WALL_Z + 3 || z == WALL_Z + 6, Direction.Axis.Z)
        }
        // Lanterns on fence posts, and plants on barrels.
        for (pos in listOf(base.offset(0, 0, WALL_Z + 1), base.offset(-ROOM_X, 0, WALL_Z + 5), base.offset(ROOM_X, 0, WALL_Z + 5))) {
            level.setBlockAndUpdate(pos, Blocks.SPRUCE_FENCE.defaultBlockState())
            level.setBlockAndUpdate(pos.above(), Blocks.LANTERN.defaultBlockState())
        }
        val plants = listOf(
            -ROOM_X to Blocks.POTTED_FERN, -2 to Blocks.POTTED_FLOWERING_AZALEA, 2 to Blocks.POTTED_AZALEA, ROOM_X to Blocks.POTTED_RED_TULIP,
        )
        for ((x, plant) in plants) {
            level.setBlockAndUpdate(base.offset(x, 0, WALL_Z + 1), Blocks.BARREL.defaultBlockState())
            level.setBlockAndUpdate(base.offset(x, 1, WALL_Z + 1), plant.defaultBlockState())
        }

        val ddz = base.offset(DDZ.first, 0, DDZ.second)
        val uno = base.offset(UNO.first, 0, UNO.second)
        for (table in listOf(ddz, uno)) placeTable(level, table)
        (level.getBlockEntity(uno) as CardTableBlockEntity).dye(DyeColor.BLUE)
        for ((side, color) in listOf(Direction.SOUTH to DyeColor.RED, Direction.EAST to DyeColor.YELLOW, Direction.NORTH to DyeColor.LIGHT_BLUE)) {
            level.setBlockAndUpdate(ddz.relative(side, StoolBlock.TABLE_DISTANCE), stool(color))
        }
        villager(level, uno, Direction.EAST, VillagerProfession.LIBRARIAN, VillagerType.PLAINS)
        villager(level, uno, Direction.NORTH, VillagerProfession.FARMER, VillagerType.TAIGA)
        villager(level, uno, Direction.WEST, VillagerProfession.CLERIC, VillagerType.DESERT)

        player.teleportTo(base.x + 0.5, base.y.toDouble(), base.z + 0.5)
        return ddz to uno
    }

    private fun wall(level: ServerLevel, bottom: BlockPos, post: Boolean, axis: Direction.Axis) {
        for (y in 0..3) level.setBlockAndUpdate(bottom.above(y), (if (post) Blocks.SPRUCE_LOG else Blocks.BOOKSHELF).defaultBlockState())
        level.setBlockAndUpdate(bottom.above(4), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis))
    }

    private fun placeTable(level: ServerLevel, center: BlockPos) {
        for (part in 0 until CardTableBlock.PARTS) {
            level.setBlockAndUpdate(
                CardTableBlock.posOf(center, part),
                TuKuaiContent.CARD_TABLE.defaultBlockState().setValue(CardTableBlock.FACING, Direction.SOUTH).setValue(CardTableBlock.PART, part),
            )
        }
    }

    private fun stool(color: DyeColor): BlockState = TuKuaiContent.STOOL.defaultBlockState().setValue(StoolBlock.COLOR, color)

    /** A villager standing at the [side] edge of the table, facing the middle. */
    private fun villager(
        level: ServerLevel,
        table: BlockPos,
        side: Direction,
        profession: ResourceKey<VillagerProfession>,
        type: ResourceKey<VillagerType>,
    ) {
        val villager = Villager(EntityType.VILLAGER, level)
        villager.setVillagerData(villager.villagerData.withProfession(level.registryAccess(), profession).withType(level.registryAccess(), type).withLevel(3))
        villager.setNoAi(true)
        villager.isSilent = true
        villager.isInvulnerable = true
        val distance = CardTableBlock.HALF_WIDTH + CardTableBlockEntity.SEAT_GAP
        val yaw = side.opposite.toYRot()
        villager.snapTo(table.x + 0.5 + side.stepX * distance, table.y.toDouble(), table.z + 0.5 + side.stepZ * distance, yaw, 0f)
        villager.yHeadRot = yaw
        villager.yBodyRot = yaw
        level.addFreshEntity(villager)
    }

    private fun command(server: TestServerContext, command: String) {
        server.runOnServer<RuntimeException> { s -> s.commands.performPrefixedCommand(s.createCommandSourceStack(), command) }
    }

    /** Moves the player to (x, y, z) from where they started, facing [facing] or the point (fx, fy, fz). */
    private fun teleport(server: TestServerContext, x: Double, y: Double, z: Double, facing: BlockPos?, fx: Double = 0.0, fy: Double = 0.0, fz: Double = 0.0) {
        server.runOnServer<RuntimeException> { s ->
            val player = s.playerList.players.first()
            if (player.isPassenger) player.stopRiding()
            val base = start!!
            val target = facing?.let { "${it.x + 0.5} ${it.y + 0.5} ${it.z + 0.5}" } ?: "${base.x + fx} ${base.y + fy} ${base.z + fz}"
            s.commands.performPrefixedCommand(
                s.createCommandSourceStack(),
                "tp ${player.plainTextName} ${base.x + x} ${base.y + y} ${base.z + z} facing $target",
            )
        }
    }

    /** Where the player started in the current world, the origin for [teleport]. */
    private var start: BlockPos? = null

    private fun waitFinished(context: ClientGameTestContext, server: TestServerContext, table: BlockPos) {
        var waited = 0
        while (status(server, table) != TableStatus.FINISHED) {
            check(waited < 12000) { "The round did not finish" }
            context.waitTicks(20)
            waited += 20
        }
    }

    private fun act(server: TestServerContext, pos: BlockPos, op: TableOp, a: Int = 0, b: Int = 0, text: String = "") {
        server.runOnServer<RuntimeException> { s ->
            val player = s.playerList.players.first()
            val table = s.overworld().getBlockEntity(pos) as CardTableBlockEntity
            table.handle(player, TableActionPayload(pos, op, a, b, text))
        }
    }

    private fun status(server: TestServerContext, pos: BlockPos): TableStatus =
        server.computeOnServer<TableStatus, RuntimeException> { s -> (s.overworld().getBlockEntity(pos) as CardTableBlockEntity).status }
}
