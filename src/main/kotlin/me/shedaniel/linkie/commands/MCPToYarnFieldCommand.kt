package me.shedaniel.linkie.commands

import discord4j.core.`object`.entity.MessageChannel
import discord4j.core.`object`.entity.User
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.core.event.domain.message.ReactionAddEvent
import discord4j.core.spec.EmbedCreateSpec
import me.shedaniel.linkie.*
import me.shedaniel.linkie.utils.dropAndTake
import me.shedaniel.linkie.utils.onlyClass
import java.time.Duration
import kotlin.math.ceil
import kotlin.math.min

object MCPToYarnFieldCommand : CommandBase {
    override fun execute(event: MessageCreateEvent, user: User, cmd: String, args: Array<String>, channel: MessageChannel) {
        if (event.guildId.orElse(null)?.asLong() == 570630340075454474)
            throw IllegalAccessException("MCP-related commands are not available on this server.")
        if (args.size !in 1..2)
            throw InvalidUsageException("!$cmd <search> [version]")
        val mappingsContainerGetter: Triple<String, Boolean, () -> MappingsContainer>
        try {
            mappingsContainerGetter = tryLoadMCPMappingContainerDoNotThrowSupplier(if (args.size == 1) getLatestMCPVersion()?.toString()
                    ?: "" else args.last()) {
                tryLoadMCPMappingContainer(getLatestMCPVersion()?.toString() ?: "", null).third()
            } ?: throw NullPointerException("Please report this issue!")
        } catch (e: NullPointerException) {
            throw NullPointerException("Version not found!\nVersions: " + mcpConfigSnapshots.filterValues { it.isNotEmpty() }.keys.sorted().joinToString { it.toString() })
        }
        val searchTerm = args.first()
        val message = channel.createEmbed {
            it.apply {
                setFooter("Requested by " + user.discriminatedName, user.avatarUrl)
                setTimestampToNow()
                var desc = "Searching up fields for **${mappingsContainerGetter.first}**."
                if (!mappingsContainerGetter.second) desc += "\nThis mappings version is not yet cached, might take some time to download."
                setDescription(desc)
            }
        }.block() ?: throw NullPointerException("Unknown Message!")

        try {
            val mappingsContainer = mappingsContainerGetter.third.invoke()
            val yarnMappingsContainer = tryLoadYarnMappingContainerDoNotThrow(mappingsContainer.version, null)?.third?.invoke()
                    ?: throw NullPointerException("Failed to find yarn version for ${mappingsContainer.version}!")

            val searchKeyOnly = searchTerm.replace('.', '/').onlyClass()
            val mcpFields = mutableMapOf<Field, Class>()
            mappingsContainer.classes.forEach { clazz ->
                clazz.fields.forEach {
                    if (it.intermediaryName.onlyClass().equals(searchKeyOnly, true) || it.mappedName?.onlyClass()?.equals(searchKeyOnly, true) == true) {
                        mcpFields[it] = clazz
                    }
                }
            }
            val remappedFields = mutableMapOf<String, String>()
            mcpFields.forEach { (mcpField, mcpClassParent) ->
                val obfName = mcpField.obfName.merged!!
                val parentObfName = mcpClassParent.obfName.merged!!
                val yarnClass = yarnMappingsContainer.getClassByObfName(parentObfName) ?: return@forEach
                val yarnField = yarnClass.fields.firstOrNull { it.obfName.merged == obfName } ?: return@forEach
                remappedFields[mcpClassParent.intermediaryName.onlyClass() + "." + (mcpField.mappedName ?: mcpField.intermediaryName)] = (yarnClass.mappedName
                        ?: yarnClass.intermediaryName).onlyClass() + "." + (yarnField.mappedName ?: yarnField.intermediaryName)
            }
            if (remappedFields.isEmpty())
                throw NullPointerException("No results found!")
            var page = 0
            val maxPage = ceil(remappedFields.size / 5.0).toInt()
            val mcpFieldsList = remappedFields.keys.toList()
            message.edit { it.setEmbed { it.buildMessage(remappedFields, mcpFieldsList, mappingsContainer.version, page, user, maxPage) } }.subscribe { msg ->
                if (channel.type.name.startsWith("GUILD_"))
                    msg.removeAllReactions().block()
                msg.subscribeReactions("⬅", "❌", "➡")
                api.eventDispatcher.on(ReactionAddEvent::class.java).filter { e -> e.messageId == msg.id }.take(Duration.ofMinutes(15)).subscribe {
                    when (it.userId) {
                        api.selfId.get() -> {
                        }
                        user.id -> {
                            if (!it.emoji.asUnicodeEmoji().isPresent) {
                                msg.removeReaction(it.emoji, it.userId).subscribe()
                            } else {
                                val unicode = it.emoji.asUnicodeEmoji().get()
                                if (unicode.raw == "❌") {
                                    msg.delete().subscribe()
                                } else if (unicode.raw == "⬅") {
                                    msg.removeReaction(it.emoji, it.userId).subscribe()
                                    if (page > 0) {
                                        page--
                                        msg.edit { it.setEmbed { it.buildMessage(remappedFields, mcpFieldsList, mappingsContainer.version, page, user, maxPage) } }.subscribe()
                                    }
                                } else if (unicode.raw == "➡") {
                                    msg.removeReaction(it.emoji, it.userId).subscribe()
                                    if (page < maxPage - 1) {
                                        page++
                                        msg.edit { it.setEmbed { it.buildMessage(remappedFields, mcpFieldsList, mappingsContainer.version, page, user, maxPage) } }.subscribe()
                                    }
                                } else {
                                    msg.removeReaction(it.emoji, it.userId).subscribe()
                                }
                            }
                        }
                        else -> msg.removeReaction(it.emoji, it.userId).subscribe()
                    }
                }
            }
        } catch (t: Throwable) {
            try {
                message.edit { it.setEmbed { it.generateThrowable(t, user) } }.subscribe()
            } catch (throwable2: Throwable) {
                throwable2.addSuppressed(t)
                throw throwable2
            }
        }
    }

    private fun EmbedCreateSpec.buildMessage(remappedFields: MutableMap<String, String>, mcpFields: List<String>, version: String, page: Int, author: User, maxPage: Int) {
        setFooter("Requested by " + author.discriminatedName, author.avatarUrl)
        setTimestampToNow()
        if (maxPage > 1) setTitle("List of MCP->Yarn Mappings (Page ${page + 1}/$maxPage)")
        var desc = ""
        mcpFields.dropAndTake(5 * page, 5).forEach {
            if (desc.isNotEmpty())
                desc += "\n"
            val yarnName = remappedFields[it]
            desc += "**MC $version: $it => `$yarnName`**\n"
        }
        setDescription(desc.substring(0, min(desc.length, 2000)))
    }

    override fun getName(): String = "MCP->Yarn Field Command"
    override fun getDescription(): String = "Query mcp->yarn fields."
}