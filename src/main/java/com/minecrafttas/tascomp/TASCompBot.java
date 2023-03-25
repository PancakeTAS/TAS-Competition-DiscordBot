package com.minecrafttas.tascomp;

import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.security.auth.login.LoginException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.minecrafttas.tascomp.GuildConfigs.ConfigValues;
import com.minecrafttas.tascomp.util.RoleWrapper;
import com.minecrafttas.tascomp.util.Util;
import com.vdurmont.emoji.EmojiManager;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.channel.update.ChannelUpdateArchivedEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu.SelectTarget;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.internal.interactions.CommandDataImpl;

public class TASCompBot extends ListenerAdapter implements Runnable {

	private static TASCompBot instance;
	private final JDA jda;
	private final Properties configuration;
	private final GuildConfigs guildConfigs;
	private final SubmissionHandler submissionHandler;
	private final ParticipateOffer offer;
	private final DMBridge dmBridgeHandler;
	private final ScheduleMessageHandler scheduleMessageHandler;
	private static final Logger LOGGER = LoggerFactory.getLogger("TAS Competition");
	public static final int color=0x0a8505;
	
	public TASCompBot(Properties configuration) throws InterruptedException, LoginException {
		instance=this;
		this.configuration = configuration;
		final JDABuilder builder = JDABuilder.createDefault(this.configuration.getProperty("token"))
				.setMemberCachePolicy(MemberCachePolicy.ALL)
                .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(this);
		this.guildConfigs = new GuildConfigs(LOGGER);
		this.submissionHandler = new SubmissionHandler(LOGGER);
		this.offer = new ParticipateOffer(guildConfigs);
		this.dmBridgeHandler = new DMBridge(LOGGER, submissionHandler, guildConfigs);
		this.scheduleMessageHandler = new ScheduleMessageHandler(LOGGER);
		this.jda = builder.build();
		this.jda.awaitReady();
	}

	@Override
	public void run() {
		/* Register the Commands */
		LOGGER.info("Preparing bot...");
		
		for (Guild guild : jda.getGuilds()) {
			prepareGuild(guild);
		}
		
		LOGGER.info("Done preparing bot!");
	}
	
	@Override
	public void onGuildJoin(GuildJoinEvent event) {
		LOGGER.info("Bot joined new guild {}.", event.getGuild().getName());
		prepareGuild(event.getGuild());
	}

	private void prepareGuild(Guild guild) {
		LOGGER.info("Preparing guild {}...", guild.getName());
		guild.loadMembers();
		prepareCommands(guild);
		guildConfigs.prepareConfig(guild);
		submissionHandler.loadSubmissionsForGuild(guild);
		scheduleMessageHandler.loadScheduledMessagesForGuild(guild);
		dmBridgeHandler.loadDMBridgesForGuild(guild);

		LOGGER.info("Done preparing guild {}!", guild.getName());
	}

	private void prepareCommands(Guild guild) {
		LOGGER.info("Preparing commands...");
		CommandListUpdateAction updater = guild.updateCommands();
		
		/*Important! The subcommands name(e.g. organizerchannel, participaterole) are made so it matches (ignoring capitalization) 
		 * the config name in GuildConfigs (ConfigValues.ORGANIZERCHANNEL, ConfigValues.PARTICIPATEROLE).
		 * This makes it easier to set the config values*/
		
		// =========================Setup
		// /setchannel
		CommandDataImpl setupCommand = new CommandDataImpl("setup", "Setup the channels for the bot");
		setupCommand.setDefaultPermissions(DefaultMemberPermissions.DISABLED);
		
		// =========================TAS Competition Start/Stop
		CommandDataImpl tascompCommand = new CommandDataImpl("tascompetition", "Starts/Stops the TAS Competition");
		tascompCommand.setDefaultPermissions(DefaultMemberPermissions.DISABLED);
		
		SubcommandData[] tascompSubcommands= {
				new SubcommandData("start", "Starts a TAS Competition in this guild"),
				new SubcommandData("stop", "Stops a running TAS Competition in this guild")
		};
		tascompCommand.addSubcommands(tascompSubcommands);
		
		// =========================== Preview
		
		CommandData previewContext = Commands.message("Preview embed");
		previewContext.setDefaultPermissions(DefaultMemberPermissions.DISABLED);

		// =========================== SetRule
		CommandDataImpl getRuleCommand = new CommandDataImpl("getrulemessage", "Get's the rule message sent after typing /participate");
		CommandData setRuleContext = Commands.message("Set rule message");
		getRuleCommand.setDefaultPermissions(DefaultMemberPermissions.DISABLED);
		setRuleContext.setDefaultPermissions(DefaultMemberPermissions.DISABLED);
		
		// =========================== Participate
		CommandDataImpl participateCommand = new CommandDataImpl("participate", "Participate in the Minecraft TAS Competition!");
		participateCommand.setDefaultPermissions(DefaultMemberPermissions.DISABLED);
		
		// =========================== Forcesubmit
		CommandDataImpl forcesubmitCommand = new CommandDataImpl("forcesubmit", "Controls submissions");
		forcesubmitCommand.setDefaultPermissions(DefaultMemberPermissions.DISABLED);

		SubcommandData forcesubmitAddSubCommand = new SubcommandData("add", "Manually adds a submission");
		OptionData userOption = new OptionData(OptionType.USER, "user", "The user of this submission");
		userOption.setRequired(true);
		OptionData forcesubmissionOption = new OptionData(OptionType.STRING, "submission", "The submission");
		forcesubmissionOption.setRequired(true);
		forcesubmitAddSubCommand.addOptions(userOption, forcesubmissionOption);

		SubcommandData submitClearSubCommand = new SubcommandData("clear", "Manually clears a submission");
		submitClearSubCommand.addOptions(userOption);

		SubcommandData submitClearAllSubCommand = new SubcommandData("clearall", "Clears all submissions");
		SubcommandData submitShowAllSubCommand = new SubcommandData("showall", "Shows all submissions");

		forcesubmitCommand.addSubcommands(forcesubmitAddSubCommand, submitClearSubCommand, submitClearAllSubCommand, submitShowAllSubCommand);
		
		// =========================== StartDM
		
		CommandDataImpl startDMCommand = new CommandDataImpl("startdm", "Starts a DM through the bot");
		OptionData userOption2 = new OptionData(OptionType.USER, "user", "The user to send this dm to");
		OptionData startingMessage = new OptionData(OptionType.STRING, "startmessage", "The message to start the DM with");
		userOption2.setRequired(true);
		startingMessage.setRequired(true);
		
		startDMCommand.addOptions(userOption2, startingMessage);
		
		
		// =========================== ScheduleMessage
		CommandDataImpl scheduleMessageCommand = new CommandDataImpl("schedulemessage", "Schedules a message to be sent by the bot");
		OptionData timestampOption = new OptionData(OptionType.STRING, "timestamp", "The timestamp when to schedule the message");
		OptionData channelOption = new OptionData(OptionType.CHANNEL, "channel", "The channel where the message will be sent");

		scheduleMessageCommand.setDefaultPermissions(DefaultMemberPermissions.DISABLED);
		
		OptionData messageIDOption = new OptionData(OptionType.STRING, "messageid", "The message id");
		
		timestampOption.setRequired(true);
		channelOption.setRequired(true);
		messageIDOption.setRequired(true);
		
		scheduleMessageCommand.addOptions(messageIDOption, timestampOption, channelOption);

		// =========================== Help
		CommandDataImpl helpCommand = new CommandDataImpl("help", "SEND HELP AHHH");
		helpCommand.setDefaultPermissions(DefaultMemberPermissions.DISABLED);
		
		SubcommandData previewHelpSubCommand = new SubcommandData("previewcommand", "Send the preview command help");
		SubcommandData setupHelpSubCommand = new SubcommandData("setup", "A checklist for setting up this bot");
		
		helpCommand.addSubcommands(previewHelpSubCommand, setupHelpSubCommand);
		
		// =========================== Test
		CommandDataImpl testCommand = new CommandDataImpl("test", "Testing things");
		testCommand.setDefaultPermissions(DefaultMemberPermissions.DISABLED);
		
		updater.addCommands(tascompCommand, setupCommand, previewContext, getRuleCommand, setRuleContext, participateCommand, forcesubmitCommand, startDMCommand, scheduleMessageCommand, helpCommand/*, testCommand*/);
		updater.queue();
		LOGGER.info("Done preparing commands!");
	}
	
	private boolean shouldExecuteParticipate(Guild guild) {
		return isCompetitionRunning(guild) && 
				guildConfigs.hasValue(guild, ConfigValues.PARTICIPATECHANNEL) && 
				guildConfigs.hasValue(guild, ConfigValues.PARTICIPATEROLE) && 
				guildConfigs.hasValue(guild, ConfigValues.RULEMSG);
	}

	@Override
	public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
		LOGGER.info("{}: Running slash command {} in {}", event.getUser().getAsTag(), event.getFullCommandName(), event.getGuild().getName());
		String commandPath = event.getFullCommandName().replace(" ", "/");
		try {
			// ================== TAS Competition Command
			if(commandPath.startsWith("tascompetition/")) {
				if (event.getSubcommandName().equals("start")) {
					if(isCompetitionRunning(event.getGuild())){
						Util.sendErrorReply(event, "The competition is already running!", "Stop it first", true);
						return;
					}
					
					guildConfigs.setValue(event.getGuild(), ConfigValues.COMPETITION_RUNNING, "true");
		
					Util.sendSelfDestructingReply(event,
							"Starting the TAS Competition-Bot. `/participate` will be enabled and listening to DM's from participants", 20);
					
				} else if (event.getSubcommandName().equals("stop")) {
					if(!isCompetitionRunning(event.getGuild())){
						Util.sendErrorReply(event, "There is no competition running!", "So there is nothing to stop", true);
						return;
					}
					
					guildConfigs.setValue(event.getGuild(), ConfigValues.COMPETITION_RUNNING, "false");
					
					Util.sendSelfDestructingReply(event,
							"Stopping the TAS Competition-Bot. Disabeling `/participate` and stop listening to DM's from participants", 20);
				}
			}
			
			// ================== Setup
			else if (commandPath.startsWith("setup")) {
				Guild guild = event.getGuild();
				
				event.reply(Util.constructEmbedMessage("Setup:", getSetup(guild), color))
				.addActionRow(EntitySelectMenu.create("participatechannelselect", SelectTarget.CHANNEL).build())
				.addActionRow(EntitySelectMenu.create("submitchannelselect", SelectTarget.CHANNEL).build())
				.addActionRow(EntitySelectMenu.create("organizerchannelselect", SelectTarget.CHANNEL).build())
				.addActionRow(EntitySelectMenu.create("participateroleselect", SelectTarget.ROLE).build())
				.addActionRow(Button.danger("clearall", "Clear All"))
				.queue(hook-> hook.retrieveOriginal().queue(msg -> msg.addReaction(Emoji.fromUnicode(Util.deletableEmoji)).queue()));
			}
			// ================== getrulemessage Command
			else if(commandPath.startsWith("getrulemessage")) {
				Util.sendReply(event, guildConfigs.getValue(event.getGuild(), ConfigValues.RULEMSG), true);
			}
			// ================== Participate Command
			else if (commandPath.equals("participate")) {
				if (shouldExecuteParticipate(event.getGuild())) {
					if (offer != null) {
						if (!RoleWrapper.doesMemberHaveRole(event.getMember(), guildConfigs.getValue(event.getGuild(), ConfigValues.PARTICIPATEROLE))) {
							offer.startOffer(event.getGuild(), event.getUser());
							Util.sendReply(event, "Check your DMs to participate!", true);
						} else {
							Util.sendErrorReply(event, "You are already participating!", "", true);
						}
					} else {
						Util.sendErrorReply(event, new NullPointerException("This state shouldn't be possible, meaning the bot author made a bug somewhere..."), true);
					}
				} else {
					Util.sendReply(event, "You can not participate currently!", true);
				}
			}
			// ================== Forcesubmit Command
			else if (commandPath.startsWith("forcesubmit")) {
				if (commandPath.equals("forcesubmit/add")) {
					submissionHandler.submit(event.getGuild(), event.getOption("user").getAsUser(), event.getOption("submission").getAsString());
				} else if (commandPath.equals("forcesubmit/clear")) {
					submissionHandler.clearSubmission(event, event.getOption("user").getAsUser());
				} else if (commandPath.equals("forcesubmit/clearall")) {
					submissionHandler.clearAllSubmissions(event);
				} else if (commandPath.equals("forcesubmit/showall")) {
					submissionHandler.sendSubmissionList(event);
				}
			}
			// =========================== StartDM
			else if (commandPath.startsWith("startdm")) {
				
				Guild guild = event.getGuild();
				User dmUser = event.getOption("user").getAsUser();
				String startMessage = event.getOption("startmessage").getAsString();
				
				ThreadChannel threadChannel = dmBridgeHandler.getThreadChannel(guild, dmUser);
				if(threadChannel != null) {
					Util.sendErrorReply(event, "A thread already exists", String.format("Use %s or close that thread", threadChannel.getAsMention()), true);
					return;
				}
				
				String channelID = guildConfigs.getValue(guild, ConfigValues.ORGANIZERCHANNEL);
				if(channelID == null) {
					Util.sendErrorReply(event, "Organizerchannel was not set", "Use `/setup` to set the channel", true);
					return;
				}
				TextChannel organizerchannel = guild.getChannelById(TextChannel.class, channelID);
				dmBridgeHandler.createDMBridge(guild, organizerchannel, dmUser, startMessage);
				Util.sendDeletableDirectMessage(dmUser, new MessageCreateBuilder().setEmbeds(Util.constructEmbedWithAuthor(event.getUser(), "", startMessage, color).setFooter("Sent from "+guild.getName()).build()).build());
				Util.sendReply(event, String.format("Created DMBridge for user %s in the channel %s", dmUser.getAsMention(), organizerchannel.getAsMention()), true);
			}
			// ================== ScheduleMessage Command
			else if (commandPath.startsWith("schedulemessage")) {
				String timestamp = event.getOption("timestamp").getAsString();
				MessageChannel channel = event.getOption("channel").getAsChannel().asGuildMessageChannel();
				String messageId = event.getOption("messageid").getAsString();
		
				event.deferReply().queue(hook ->{
					try {
						scheduleMessageHandler.scheduleMessage(event.getGuild(), event.getChannel().asGuildMessageChannel(), event.getUser(), messageId, channel, timestamp);
					} catch (Exception e) {
						Util.sendErrorMessage(event.getChannel(), e);
						e.printStackTrace();
					} finally {
						hook.deleteOriginal().queue();
					}
				});
			}
			// ================== Help Command
			else if(commandPath.startsWith("help")) {
				if(commandPath.equals("help/previewcommand")) {
					String mdhelp = "Everything outside the md block will be parsed as a message and not as an embed!"
							+ "```md\n"
							+ "# Markdown to Embed\n"
							+ "This system allows you to construct message embeds by the bot via a markdown message.\n"
							+ "Rightclicking a message -> Apps -> Preview embed, allows you to display the embed.\n"
							+ "This help is sent via the command `/help previewcommand`\n"
							+ "\n"
							+ "This part of the embed is called the description and is used as the main thing for content.\n"
							+ "One # at the start indicates the title of the embed.\n"
							+ "## Fields\n"
							+ "Fields are sub headings designed to list something. ## Shows the title of the field and seperates the different fields.\n"
							+ "## Second field\n"
							+ "This is a second field for more categorization of the embed.\n"
							+ "```";
					
					Util.sendReply(event, mdhelp, true, MD2Embed.parseEmbed(mdhelp, color).build());
				}
				else if(commandPath.equals("help/setup")) {
					String setuphelp = "```md\n"
							+ "# Setup\n"
							+ "Here is a checklist for setting up the bot:\n"
							+ "## 1. Create channels and roles\n"
							+ "This bot is designed to have 3 different channels:\n"
							+ "-`Organizer Channel`: If you DM the bot as a participant, the messages will get forwarded to this channel\n"
							+ "-`Submission Channel`: If a participant submits a video via `!submit` this will get forwarded to this channel. Reason is that you want to seperate submissions from conversations\n"
							+ "-`Participate Channel`: Channel where people can type /participate to get the participate role. The bot will leave a message there to show that it worked.\n"
							+ "\n"
							+ "-`Participate Role`: The role for participants. Decides if they are allowed to DM the bot and submit\n"
							+ "-`Organizer Role`: A role not used by the bot, but useful for permissions -> See section 3\n"
							+ "\n"
							+ "## 2. Set the channels and roles to the config\n"
							+ "Executing the command `/setup` will give you 4 dropdowns, where you can set the channels and the roles, or disable all of them\n"
							+ "## 3. Setup permissions in integration settings\n"
							+ "Due to slash commands being relatively new, you can't set permissions programmatically and have to manually add them in the server settings.\n"
							+ "All commands are disabled by default to everyone.\n"
							+ "Suggested command permissions:"
							+ "*Everyone:* participate\n"
							+ "*Organizers:* tascompetition, startdm, schedulemessage, preview embed, forcesubmit, help\n"
							+ "*Admins:*: setup, setrulemessage, getrulemessage\n"
							+ "\n"
							+ "*Participate Channel:* participate\n"
							+ "*Submission Channel:* forcesubmit\n"
							+ "*Organizerchannel:* tascompetition, startdm, schedulemessage, setup (set rule message)\n"
							+ "The following commands send a private response only to you:\n"
							+ "preview embed, getrulemessage, help\n"
							+ "## 4. Set the rule message\n"
							+ "When a user runs `/participate` the bot will dm users with the rules and a captcha which they have to solve to be granted with the participate role.\n"
							+ "You have to set these rules by first writing a markdown message, rightclicking it -> Apps -> Set rule message to set it.\n"
							+ "You can retrieve the current rule message by executing `/getrulemessage`\n"
							+ "Further instructions on how to write markdown messages are in `/help previewcommand`\n"
							+ "## 5. Start the TAS Competition\n"
							+ "Use `/tascompetition start` to start the competition\n"
							+ "Users can head to the participate channel and type /participate. The bot will DM them with the rules and a captcha which they have to solve to be granted with the participate role\n"
							+ "Once that is done, users can send the bot DM's which will get forwarded to the organizerchannel. Organizers can reply to the bot messages to answer in DM's\n"
							+ "Users can submit with !submit <message> to add a submission to the submission channel\n"
							+ "\n"
							+ "Use `/help commands` to see all commands\n"
							+ "```";
					System.out.println(setuphelp.length());
					MessageCreateData msg1=new MessageCreateBuilder().setEmbeds(MD2Embed.parseEmbed(setuphelp, color).build()).build();
					Util.sendReply(event, msg1, true);
				}
			}
		}catch (Exception e) {
			Util.sendErrorReply(event, e, true);
			e.printStackTrace();
		}
	}
	
	private String getSetup(Guild guild) {
		
		String participate = guildConfigs.getValue(guild, ConfigValues.PARTICIPATECHANNEL);
		String submit = guildConfigs.getValue(guild, ConfigValues.SUBMITCHANNEL);
		String organizer = guildConfigs.getValue(guild, ConfigValues.ORGANIZERCHANNEL);
		String partrole = guildConfigs.getValue(guild, ConfigValues.PARTICIPATEROLE);
		
		participate = participate == null? "unset" : "<#"+participate+">";
		submit = submit == null? "unset" : "<#"+submit+">";
		organizer = organizer == null? "unset" : "<#"+organizer+">";
		partrole = partrole == null? "unset" : "<@&"+partrole+">";
		
		return String.format(""
				+ "1. Participatechannel. Current: %s\n"
				+ "2. Submitchannel. Current: %s\n"
				+ "3. Organizerchannel. Current: %s\n"
				+ "4. Participantrole. Current: %s", participate, submit, organizer, partrole);
	}
	
	@Override
	public void onMessageContextInteraction(MessageContextInteractionEvent event) {
		// ================== Preview Command
		if (event.getName().equals("Preview embed")) {
			try {
				Message msg = event.getTarget();
				MessageCreateBuilder newmsg = MD2Embed.parseMessage(msg, color);
				Util.sendReply(event, newmsg.build(), true);
			} catch (Exception e) {
				Util.sendErrorReply(event, e, true);
				e.printStackTrace();
			}
		}
		// ================== SetRule Command
		else if(event.getName().equals("Set rule message")) {
			try {
				Message msg = event.getTarget();
				guildConfigs.setValue(event.getGuild(), ConfigValues.RULEMSG, msg.getContentRaw());
				MessageCreateBuilder builder = new MessageCreateBuilder();
				builder.setContent("Set the rule message to:");
				builder.setEmbeds(MD2Embed.parseEmbed(msg.getContentRaw(), color).build());
				Util.sendReply(event, builder.build(), true);
			} catch (Exception e) {
				Util.sendErrorReply(event, e, true);
				e.printStackTrace();
			}
		}
		// ================== Schedule Message
		else if(event.getName().equals("Schedule message")) {
			try {
				Message msg = event.getTarget();
				MessageCreateBuilder builder = new MessageCreateBuilder();
				builder.setContent("`/schedulemessage "+msg.getId()+"`");
				Util.sendReply(event, builder.build(), true);
			} catch (Exception e) {
				Util.sendErrorReply(event, e, true);
				e.printStackTrace();
			}
		}
	}
	
	@Override
	public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
		if(event.getComponentId().equals("participatechannelselect")) {
			GuildChannel channel = event.getMentions().getChannels().get(0);
			guildConfigs.setValue(event.getGuild(), ConfigValues.PARTICIPATECHANNEL, channel.getId());
			event.deferReply().queue(hook->{
				hook.retrieveOriginal().queue(msg->{
					msg.getMessageReference().getChannel().editMessageEmbedsById(msg.getMessageReference().getMessageId(), Util.constructEmbed("Setup:", getSetup(event.getGuild()), color)).queue();
				});
				hook.deleteOriginal().queue();
			});
//			Util.sendReply(event, "Set the Participate Channel to "+channel.getAsMention(), true);
		}
		else if(event.getComponentId().equals("submitchannelselect")) {
			GuildChannel channel = event.getMentions().getChannels().get(0);
			guildConfigs.setValue(event.getGuild(), ConfigValues.SUBMITCHANNEL, channel.getId());
			event.deferReply().queue(hook->{
				hook.retrieveOriginal().queue(msg->{
					msg.getMessageReference().getChannel().editMessageEmbedsById(msg.getMessageReference().getMessageId(), Util.constructEmbed("Setup:", getSetup(event.getGuild()), color)).queue();
				});
				hook.deleteOriginal().queue();
			});
//			Util.sendReply(event, "Set the Submitting Channel to "+channel.getAsMention(), true);
		}
		else if(event.getComponentId().equals("organizerchannelselect")) {
			GuildChannel channel = event.getMentions().getChannels().get(0);
			guildConfigs.setValue(event.getGuild(), ConfigValues.ORGANIZERCHANNEL, channel.getId());
			event.deferReply().queue(hook->{
				hook.retrieveOriginal().queue(msg->{
					msg.getMessageReference().getChannel().editMessageEmbedsById(msg.getMessageReference().getMessageId(), Util.constructEmbed("Setup:", getSetup(event.getGuild()), color)).queue();
				});
				hook.deleteOriginal().queue();
			});
//			Util.sendReply(event, "Set the Organizer Channel to "+channel.getAsMention(), true);
		}
		else if(event.getComponentId().equals("participateroleselect")) {
			Role role = event.getMentions().getRoles().get(0);
			guildConfigs.setValue(event.getGuild(), ConfigValues.PARTICIPATEROLE, role.getId());
			event.deferReply().queue(hook->{
				hook.retrieveOriginal().queue(msg->{
					msg.getMessageReference().getChannel().editMessageEmbedsById(msg.getMessageReference().getMessageId(), Util.constructEmbed("Setup:", getSetup(event.getGuild()), color)).queue();
				});
				hook.deleteOriginal().queue();
			});
//			Util.sendReply(event, "Set the Participate Role to "+role.getAsMention(), true);
		}
	}
	
	@Override
	public void onButtonInteraction(ButtonInteractionEvent event) {
		
		if(event.getComponentId().equals("clearall")) {
			guildConfigs.removeValues(event.getGuild(), ConfigValues.PARTICIPATECHANNEL, ConfigValues.SUBMITCHANNEL, ConfigValues.ORGANIZERCHANNEL, ConfigValues.PARTICIPATEROLE);
			event.deferReply().queue(hook->{
				hook.retrieveOriginal().queue(msg->{
					msg.getMessageReference().getChannel().editMessageEmbedsById(msg.getMessageReference().getMessageId(), Util.constructEmbed("Setup:", getSetup(event.getGuild()), color)).queue();
				});
				hook.deleteOriginal().queue();
			});
		}
	}
	
	@Override
	public void onMessageReactionAdd(MessageReactionAddEvent event) {
		if (!Util.isThisUserThisBot(event.getUserIdLong())) {

			Emoji reactionEmote = event.getEmoji();

			if (reactionEmote.getFormatted().equals(EmojiManager.getForAlias(":x:").getUnicode())) {

				event.retrieveMessage().queue(msg -> {
					if (Util.isThisUserThisBot(msg.getAuthor())) {

						if (Util.hasBotReactedWith(msg, EmojiManager.getForAlias(":x:").getUnicode()) || event.getChannelType() == ChannelType.PRIVATE) {
							Util.deleteMessage(msg);
						}
					}
				});
			}

			// DMBridge Send
			else if (event.getChannelType() == ChannelType.PRIVATE) {
				event.retrieveMessage().queue(msg -> {
					if (Util.hasBotReactedWith(msg, reactionEmote.getFormatted())) {
						dmBridgeHandler.processPrivateReactions(msg, reactionEmote, event.getUser());
					}
				});
			}
		}
	}
	
	public static TASCompBot getBot() {
		return instance;
	}

	public JDA getJDA() {
		return jda;
	}
	
	@Override
	public void onMessageReceived(MessageReceivedEvent event) {
		if (event.getChannelType() == ChannelType.PRIVATE) {
			event.getChannel().retrieveMessageById(event.getMessageId()).queue(message -> {
				if (!Util.isThisUserThisBot(event.getAuthor())) {
					String msg = message.getContentRaw();

					// Accept
					String accept = MD2Embed.matchAndGet("^!accept (\\w{5})", msg, 1);

					if (accept != null) {
						User user = message.getAuthor();
						Guild guild = offer.checkCode(user, accept);
						if (guild != null) {
							Util.sendSelfDestructingDirectMessage(user, "You are now participating!", 20);
							sendPrivateCommandHelp(user);

							Role participationRole = guild.getRoleById(guildConfigs.getValue(guild, ConfigValues.PARTICIPATEROLE));

							guild.addRoleToMember(user, participationRole).queue();

							MessageChannel channel = (MessageChannel) guild.getGuildChannelById(guildConfigs.getValue(guild, ConfigValues.PARTICIPATECHANNEL));

							MessageCreateData guildMessage = Util.constructEmbedMessage(user.getName() + " is now participating!", "Type `/participate` if you also want to join the TAS Competition!", color);

							Util.sendMessage(channel, guildMessage);
						}

					} else if (Pattern.matches("^!servers", msg)) {

						dmBridgeHandler.sendActiveCompetitions(event.getAuthor());

					} else if (Pattern.matches("^!help", msg)) {
						sendPrivateCommandHelp(event.getAuthor());
					} else {
						dmBridgeHandler.setupReactions(message);
						
						List<Guild> guilds = DMBridge.getActiveParticipationGuilds(event.getAuthor());
						if(guilds.size()>0 && Pattern.matches("^!submit (.+)", message.getContentRaw()) && !dmBridgeHandler.hasSubmitted(event.getAuthor(), guilds)) {
							Util.sendDeletableDirectMessage(event.getAuthor(), "*Tip:*\nTo send off the submission, react with "+dmBridgeHandler.singleGuildEmoji.getAsReactionCode()+" to your message");
						}
					}
				}
			});
		}
		else if (event.getChannel().getType() == ChannelType.GUILD_PUBLIC_THREAD) {
			ThreadChannel threadChannel = event.getChannel().asThreadChannel();
			if(threadChannel.getParentMessageChannel().getId().equals(guildConfigs.getValue(event.getGuild(), ConfigValues.ORGANIZERCHANNEL)) && !Util.isThisUserThisBot(event.getAuthor())) {
				dmBridgeHandler.sendDM(event.getGuild(), threadChannel, event.getMessage());
			}
		}
	}
	
	private void sendPrivateCommandHelp(User user) {
		LOGGER.info("Sending private help to "+ user.getName());
		EmbedBuilder builder = new EmbedBuilder();
		builder.setTitle("Help/Commands");
		builder.setDescription("When DMing this bot, your message will get forwarded to the organizers. They can also answer you through the bot.\n\n"
				+ "When you send a message, the bot will react with a \uD83D\uDCE8. Reacting to that will forward the message to the organizers.\n"
				+ "If the message was sent correctly, the bot will react with a \u2709\uFE0F\n\n"
				+ "There are also commands you can use in DM's:");
		
		builder.addField("!submit <link to submission and/or meme run>", "Adds a submission. To overwrite the last submission, just use `!submit` again.\n\n"
				+ "*Example:* `!submit Submission: https://www.youtube.com/watch?v=3Tk6WaigTQk MemeRun: https://www.youtube.com/watch?v=dQw4w9WgXcQ`",false);
		
		builder.addField("!servers", "A list of servers hosting a TAS Competition and in which you are participating. Useful if this bot is used for multiple TAS Competitions on different servers", false);
		builder.addField("!help", "Get this help again", false);
		builder.setColor(color);
		Util.sendDeletableDirectMessage(user, new MessageCreateBuilder().setEmbeds(builder.build()).build());
	}

	public GuildConfigs getGuildConfigs() {
		return guildConfigs;
	}

	public boolean isCompetitionRunning(Guild guild) {
		return guildConfigs.getValue(guild, ConfigValues.COMPETITION_RUNNING).equals("true");
	}
	
	@Override
	public void onMessageDelete(MessageDeleteEvent event) {
		if(event.isFromGuild()) {
			scheduleMessageHandler.onDelete(event.getGuild(), event.getMessageIdLong());
		}
	}
	
	@Override
	public void onChannelDelete(ChannelDeleteEvent event) {
		if(event.getChannel().getType() == ChannelType.GUILD_PUBLIC_THREAD) {
			dmBridgeHandler.removeThread(event.getGuild(), event.getChannel().asThreadChannel());
		}
	}
	
	@Override
	public void onChannelUpdateArchived(ChannelUpdateArchivedEvent event) {
		if(event.getChannel().getType() == ChannelType.GUILD_PUBLIC_THREAD) {
			dmBridgeHandler.removeThread(event.getGuild(), event.getChannel().asThreadChannel());
		}
	}
}
