package org.rsmod

import com.github.michaelbull.logging.InlineLogger
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Scopes
import dev.misfitlabs.kotlinguice4.getInstance
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import java.net.InetSocketAddress
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.rsmod.game.Game
import org.rsmod.game.GameModule
import org.rsmod.game.cache.CacheModule
import org.rsmod.game.cache.ConfigTypeLoaderList
import org.rsmod.game.cache.GameCache
import org.rsmod.game.config.ConfigModule
import org.rsmod.game.config.GameConfig
import org.rsmod.game.coroutine.CoroutineModule
import org.rsmod.game.coroutine.IoCoroutineScope
import org.rsmod.game.dispatch.DispatcherModule
import org.rsmod.game.event.EventBus
import org.rsmod.game.event.impl.ServerStartup
import org.rsmod.game.plugin.kotlin.KotlinModuleLoader
import org.rsmod.game.plugin.kotlin.KotlinPluginLoader
import org.rsmod.game.task.StartupTaskList
import org.rsmod.game.task.launchBlocking
import org.rsmod.game.task.launchNonBlocking
import org.rsmod.net.NetworkModule
import org.rsmod.net.channel.ClientChannelInitializer
import org.rsmod.net.handshake.HandshakeDecoder

private val logger = InlineLogger()

fun main() {
    val server = Server()
    server.startup()
}

class Server {

    fun startup() = runBlocking {
        logger.info { "Starting up server - please wait..." }

        val scope = Scopes.SINGLETON

        val moduleLoader = KotlinModuleLoader(scope)
        val moduleScripts = moduleLoader.load()
        val modules = moduleScripts.flatMap { it.modules }

        val injector = Guice.createInjector(
            ServerModule(scope),
            CoroutineModule(scope),
            DispatcherModule(scope),
            ConfigModule(scope),
            CacheModule(scope),
            GameModule(scope),
            NetworkModule(scope),
            *modules.toTypedArray()
        )
        val gameConfig: GameConfig = injector.getInstance()
        logger.info { "Launching ${gameConfig.name}" }

        val cache: GameCache = injector.getInstance()
        cache.start()

        val ioCoroutineScope: IoCoroutineScope = injector.getInstance()

        val typeLoaders: ConfigTypeLoaderList = injector.getInstance()
        loadConfigTypes(ioCoroutineScope, typeLoaders)

        val pluginLoader: KotlinPluginLoader = injector.getInstance()
        val plugins = pluginLoader.load()

        val startupTasks: StartupTaskList = injector.getInstance()
        startupTasks.launchNonBlocking(ioCoroutineScope)
        startupTasks.launchBlocking()

        val game: Game = injector.getInstance()
        game.start()

        bind(injector)

        logger.info { "Loaded ${plugins.size} plugin(s)" }
        logger.debug { "Loaded game with configuration: $gameConfig" }
        logger.info { "Game listening to connections on port ${gameConfig.port}" }

        val eventBus: EventBus = injector.getInstance()
        eventBus.publish(ServerStartup())
    }

    private fun bind(injector: Injector) {
        val channelInitializer = ClientChannelInitializer(
            handshakeDecoder = { injector.getInstance<HandshakeDecoder>() }
        )

        val bootstrap = ServerBootstrap()
        bootstrap.channel(NioServerSocketChannel::class.java)
        bootstrap.childHandler(channelInitializer)
        bootstrap.group(NioEventLoopGroup(2), NioEventLoopGroup(1))

        val config: GameConfig = injector.getInstance()
        val bind = bootstrap.bind(InetSocketAddress(config.port)).awaitUninterruptibly()
        if (!bind.isSuccess) {
            error("Could not bind game port.")
        }
    }
}

private fun loadConfigTypes(
    ioCoroutineScope: IoCoroutineScope,
    loaders: ConfigTypeLoaderList
) = runBlocking {
    val jobs = mutableListOf<Job>()
    loaders.forEach { loader ->
        val job = ioCoroutineScope.launch { loader.load() }
        jobs.add(job)
    }
    joinAll(*jobs.toTypedArray())
}
