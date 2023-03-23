package com.minecrafttas.tascomp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.InvalidPropertiesFormatException;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;

import com.minecrafttas.tascomp.GuildConfigs.ConfigValues;
import com.minecrafttas.tascomp.util.Util;
import com.vdurmont.emoji.EmojiManager;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

public class DMBridge {
	
	private static Logger LOGGER;
	
	public final Emoji singleGuildEmoji=Emoji.fromUnicode(EmojiManager.getForAlias("incoming_envelope").getUnicode());

	private SubmissionHandler submissionHandler;

	private GuildConfigs guildConfigs;
	
	private List<String> multiParticipationWarning = new ArrayList<>();
	
	private File dmBridgeDir = new File("dmbridge/");
	
	private HashMap<Long, Properties> dmBridgeChannels = new HashMap<>();
	
	public DMBridge(Logger logger, SubmissionHandler submissionHandler, GuildConfigs guildConfigs) {
		LOGGER=logger;
		LOGGER.info("Preparing private message handler...");
		this.submissionHandler=submissionHandler;
		this.guildConfigs=guildConfigs;
		if(!dmBridgeDir.exists()) {
			dmBridgeDir.mkdir();
		}
	}
	
	public void loadDMBridgesForGuild(Guild guild) {
		File bridgeFile = new File(dmBridgeDir, guild.getId() + ".xml");
		if(bridgeFile.exists()) {
			Properties prop = loadDMBridge(guild, bridgeFile);
			
			Properties tempProp = (Properties) prop.clone();
			
			tempProp.forEach((threadchannelID, user)->{
				ThreadChannel threadchannel = guild.getChannelById(ThreadChannel.class, (String)threadchannelID);
				if(threadchannel == null || threadchannel.isArchived()) {
					LOGGER.warn("Removing channel {} from DMBridge", threadchannelID);
					prop.remove(threadchannelID);
				}
			});
			dmBridgeChannels.put(guild.getIdLong(), prop);
			saveDMBridges(guild, prop);
		}
	}
	
	public void saveDMBridges(Guild guild, Properties dmBridges) {

		LOGGER.info("Saving DMBridge channels for guild {}...", guild.getName());
		File scheduleMessageConfig = new File(dmBridgeDir, guild.getId() + ".xml");

		try {
			FileOutputStream fos = new FileOutputStream(scheduleMessageConfig);
			dmBridges.storeToXML(fos, "DMBridge users and thread channels for guild: " + guild.getName(), "UTF-8");
			fos.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public Properties loadDMBridge(Guild guild, File bridgeFile) {
		LOGGER.info("Loading DM Bridge channels for guild {}...", guild.getName());
		Properties guildConfig = new Properties();
		try {
			FileInputStream fis = new FileInputStream(bridgeFile);
			guildConfig.loadFromXML(fis);
			fis.close();
		} catch (InvalidPropertiesFormatException e) {
			e.printStackTrace();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return guildConfig;
	}
	
	public void setupReactions(Message message) {
		
		List<Guild> participationGuilds = DMBridge.getParticipationGuilds(message.getAuthor());
		
		if (participationGuilds.size() == 1) {
			Guild guild = participationGuilds.get(0);
			if(TASCompBot.getBot().isCompetitionRunning(guild) || hasActiveThread(guild, message.getAuthor())) {
				message.addReaction(singleGuildEmoji).queue();
			}

		} else if (participationGuilds.size() > 1 && participationGuilds.size() < 10) {
			
			if(!multiParticipationWarning.contains(message.getAuthor().getAsTag())) {
				multiParticipationWarning.add(message.getAuthor().getAsTag());
				sendActiveCompetitions(message.getAuthor());
			}
			
			for (int i = 1; i <= participationGuilds.size(); i++) {
				Guild guild = participationGuilds.get(i-1);
				if(TASCompBot.getBot().isCompetitionRunning(guild) || hasActiveThread(guild, message.getAuthor())) {
					message.addReaction(intToEmoji(i)).queue();
				}
			}
		}
	}
	
	public void processPrivateReactions(Message message, Emoji reactionEmote, User dmUser) {
		
		// React with a envelope when participation guild, react with more if multiple
		Guild participationGuild = getParticipationGuild(message, reactionEmote);
		
		if(participationGuild==null) {
			return;
		}
		
		try {
			//Submit command
			String submit = MD2Embed.matchAndGet("^!submit (.+)", message.getContentRaw(), 1);
			
			if(submit!=null) {
				
				if(TASCompBot.getBot().isCompetitionRunning(participationGuild)) {
					if (submit.length() > 1024) {
						Util.sendErrorDirectMessage(dmUser, "The submission is too long!", "A submission has a maximum char length of 1024 characters.\n" + "Edit your message and react with " + singleGuildEmoji.getAsReactionCode() + " to try again.");
						return;
					}
					User user = message.getAuthor();
					submissionHandler.submit(participationGuild, user, message, submit);
				}
				else {
					Util.sendErrorDirectMessage(dmUser, "Submission period ended", "You can no longer submit for this TAS competition. However, still DM the organizers!");
				}
				
			} else {
				ThreadChannel threadChannel = getThreadChannel(participationGuild, dmUser);
				
				if(threadChannel==null) {
					if(!TASCompBot.getBot().isCompetitionRunning(participationGuild)) {
						return;
					}
					if(!guildConfigs.hasValue(participationGuild, ConfigValues.ORGANIZERCHANNEL)) {
						Util.sendDeletableDirectMessage(dmUser, "The destination channel for "+participationGuild.getName()+" was not set by their admins. You may alert them of this mistake...");
						return;
					}
					MessageChannel organizerchannel = participationGuild
							.getChannelById(MessageChannel.class, guildConfigs.getValue(participationGuild, ConfigValues.ORGANIZERCHANNEL));
					
					String initialMessage = message.getContentRaw();
					initialMessage+=Util.getAttachmentsAsString(message);
					createDMBridge(participationGuild, organizerchannel, dmUser, initialMessage);
				} else {
					Util.sendMessage(threadChannel, message.getContentRaw());
				}
			}
		} catch(Exception e) {
			Util.sendErrorDirectMessage(dmUser, e);
			return;
		}
		
		message.removeReaction(reactionEmote).queue();
		Emoji envelope = Emoji.fromUnicode(EmojiManager.getForAlias(":envelope:").getUnicode());
		message.addReaction(envelope).queue();
	}
	
	public void createDMBridge(Guild guild, MessageChannel channel, User dmUser, String initialMessage) {
		if(channel.getType() == ChannelType.TEXT) {
			TextChannel textchannel = (TextChannel) channel;
			
			textchannel.createThreadChannel(dmUser.getAsTag()+" - "+initialMessage).queue(threadchannel ->{
				
				Properties prop = dmBridgeChannels.containsKey(guild.getIdLong()) ? dmBridgeChannels.get(guild.getIdLong()) : new Properties();
				
				prop.put(threadchannel.getId(), dmUser.getAsTag());
				dmBridgeChannels.put(guild.getIdLong(), prop);
				saveDMBridges(guild, prop);
				Util.sendMessage(threadchannel, initialMessage);
			});
		}
		
	}

	public static List<Guild> getActiveParticipationGuilds(User userIn) {
		
		List<Guild> guilds = TASCompBot.getBot().getJDA().getGuilds();
		List<Guild> participateGuilds = new ArrayList<>();
		for (Guild guild : guilds) {
			String roleID = TASCompBot.getBot().getGuildConfigs().getValue(guild, ConfigValues.PARTICIPATEROLE);
			boolean isRunning = TASCompBot.getBot().getGuildConfigs().getValue(guild, ConfigValues.COMPETITION_RUNNING).equals("true");
			if (roleID == null || !isRunning) {
				continue;
			}
			Role role = guild.getRoleById(roleID);
			Member member = guild.getMemberById(userIn.getIdLong());
			if (member.getRoles().contains(role)) {
				participateGuilds.add(guild);
			}
		}
		return participateGuilds;
	}
	
	public static List<Guild> getParticipationGuilds(User userIn) {
		
		List<Guild> guilds = TASCompBot.getBot().getJDA().getGuilds();
		List<Guild> participateGuilds = new ArrayList<>();
		for (Guild guild : guilds) {
			String roleID = TASCompBot.getBot().getGuildConfigs().getValue(guild, ConfigValues.PARTICIPATEROLE);
			if (roleID == null) {
				continue;
			}
			Role role = guild.getRoleById(roleID);
			Member member = guild.getMemberById(userIn.getIdLong());
			if (member.getRoles().contains(role)) {
				participateGuilds.add(guild);
			}
		}
		return participateGuilds;
	}
	
	private Guild getParticipationGuild(Message messsage, Emoji reactionEmote) {
		// Get participation guilds
		List<Guild> participationGuilds = getParticipationGuilds(messsage.getAuthor());
		
		if (participationGuilds.size() == 1 && reactionEmote.equals(singleGuildEmoji)) {
			
			return participationGuilds.get(0);
			
		} else if (participationGuilds.size() > 1 && participationGuilds.size() < 10 && hasNumberEmoji(reactionEmote.getFormatted())) {
			int channelNumber = 0;
			if(EmojiManager.containsEmoji(reactionEmote.getFormatted())) {
				
				String emoji=reactionEmote.getFormatted();
				
				channelNumber = unicodeToInt(emoji);
			}
			return participationGuilds.get(channelNumber-1);
		} else {
			return null;
		}
	}

	public void sendActiveCompetitions(User user) {
		EmbedBuilder builder = new EmbedBuilder();
		builder.setTitle("Active TAS Competitions");
		builder.setDescription("This list shows you all TAS Competitions that you are currently participating in and the emoji you need react in order to submit something.\n\n"
				+ "If there is only one server you are participating in, the bot will react with a \uD83D\uDCE8. Reacting to that will forward the message to the organizers.\n\n"
				+ "If you participate in multiple servers, the server will react with 1\uFE0F\u20E3, 2\uFE0F\u20E3, 3\uFE0F\u20E3 etc... Use this list to look up the correct server\n\n"
				+ "`!servers` will display this info again");
		List<Guild> guilds = DMBridge.getActiveParticipationGuilds(user);
		int i=1;
		for (Guild guild : guilds) {
			builder.addField("", i+"\uFE0F\u20E3 "+guild.getName(), false);
			i++;
		}
		builder.setColor(TASCompBot.color);
		MessageCreateData msg = new MessageCreateBuilder().setEmbeds(builder.build()).build();
		Util.sendDeletableDirectMessage(user, msg);
	}
	
	public static int emojiToInt(Emoji emoji) {
		return unicodeToInt(emoji.getFormatted());
	}
	
	public static int unicodeToInt(String emoji) {
		switch (emoji) {
		case "1\uFE0F\u20E3":
			return 1;
		case "2\uFE0F\u20E3":
			return 2;
		case "3\uFE0F\u20E3":
			return 3;
		case "4\uFE0F\u20E3":
			return 4;
		case "5\uFE0F\u20E3":
			return 5;
		case "6\uFE0F\u20E3":
			return 6;
		case "7\uFE0F\u20E3":
			return 7;
		case "8\uFE0F\u20E3":
			return 8;
		case "9\uFE0F\u20E3":
			return 9;
		default:
			return 0;
		}
	}

	public static Emoji intToEmoji(int i) {
		return Emoji.fromUnicode(intToUnicode(i));
	}
	
	public static String intToUnicode(int i) {
		switch (i) {
		case 1:
			return EmojiManager.getForAlias("one").getUnicode();
		case 2:
			return EmojiManager.getForAlias("two").getUnicode();
		case 3:
			return EmojiManager.getForAlias("three").getUnicode();
		case 4:
			return EmojiManager.getForAlias("four").getUnicode();
		case 5:
			return EmojiManager.getForAlias("five").getUnicode();
		case 6:
			return EmojiManager.getForAlias("six").getUnicode();
		case 7:
			return EmojiManager.getForAlias("seven").getUnicode();
		case 8:
			return EmojiManager.getForAlias("eight").getUnicode();
		case 9:
			return EmojiManager.getForAlias("nine").getUnicode();
		default:
			return null;
		}
	}
	
	public static boolean hasNumberEmoji(Emoji emoji) {
		return hasNumberEmoji(emoji.getFormatted());
	}
	
	public static boolean hasNumberEmoji(String emoji) {
		switch (emoji) {
		case "1\uFE0F\u20E3":
			return true;
		case "2\uFE0F\u20E3":
			return true;
		case "3\uFE0F\u20E3":
			return true;
		case "4\uFE0F\u20E3":
			return true;
		case "5\uFE0F\u20E3":
			return true;
		case "6\uFE0F\u20E3":
			return true;
		case "7\uFE0F\u20E3":
			return true;
		case "8\uFE0F\u20E3":
			return true;
		case "9\uFE0F\u20E3":
			return true;
		default:
			return false;
		}
	}
	
	public boolean hasSubmitted(User author, List<Guild> guilds) {
		for(Guild guild: guilds) {
			if(submissionHandler.hasSubmitted(author, guild)) {
				return true;
			}
		}
		return false;
	}
	
	public ThreadChannel getThreadChannel(Guild guild, User user) {
		
		Properties prop = dmBridgeChannels.get(guild.getIdLong());
		
		if(prop==null) {
			return null;
		}
		
		Set<Object> keySet = prop.keySet();
		
		for(Object key : keySet) {
			String userValue = prop.getProperty((String) key);
			if(user.getAsTag().equals(userValue)) {
				return guild.getThreadChannelById((String)key);
			}
		}
		return null;
	}
	
	private User getUser(Guild guild, String threadChannelId) {
		
		Properties prop = dmBridgeChannels.get(guild.getIdLong());
		
		if(prop==null) {
			return null;
		}
		
		String userTag = (String) prop.get(threadChannelId);
		
		return guild.getMemberByTag(userTag).getUser();
	}

	public void removeThread(Guild guild, ThreadChannel channel) {
		Properties prop = dmBridgeChannels.get(guild.getIdLong());
		if(prop==null) return;
		LOGGER.info("Removing {} from DMBridge", channel.getAsMention());
		prop.remove(channel.getId());
		saveDMBridges(guild, prop);
	}

	public void sendDM(Guild guild, ThreadChannel threadChannel, Message msg) {
		EmbedBuilder builder = Util.constructEmbedWithAuthor(msg, "", msg.getContentRaw(), TASCompBot.color, true);
		builder.setFooter("Sent from "+guild.getName());
		MessageCreateData newMsg = new MessageCreateBuilder().setEmbeds(builder.build()).build();
		sendDM(guild, threadChannel, newMsg);
	}
	
	public void sendDM(Guild guild, ThreadChannel threadChannel, MessageCreateData msg) {
		Properties prop = dmBridgeChannels.get(guild.getIdLong());
		if(prop==null || !prop.containsKey(threadChannel.getId())) {
			return;
		}
		User user = getUser(guild, threadChannel.getId());
		LOGGER.info("Sending dmbridge to {}", user.getAsTag());
		Util.sendDeletableDirectMessage(user, msg);
	}
	
	public boolean hasActiveThread(Guild guild, User user) {
		return getThreadChannel(guild, user)!=null;
	}
}