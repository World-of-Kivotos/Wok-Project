package com.miningdim.social;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 社交平板的持久化根对象。字段全部使用 String UUID 键，避免 Gson 对 UUID 的隐式适配差异，
 * 也便于手工排查存档。所有集合保持插入顺序：好友列表、动态流与聊天记录都按时间稳定显示。
 *
 * <p>本类只承载数据形状，不写业务规则。写入与校验在 {@link SocialStore}。</p>
 */
public final class SocialData {

    /** 存档版本，预留给后续迁移。 */
    public int version = 1;

    /** 玩家档案，key = UUID 字符串。 */
    public Map<String, Profile> profiles = new LinkedHashMap<>();

    /** 好友关系，key = 玩家 UUID，value = 对方 UUID 列表（双向各存一份）。 */
    public Map<String, List<String>> friends = new LinkedHashMap<>();

    /** 收到的好友请求，key = 被请求方 UUID，value = 发起方 UUID 列表。 */
    public Map<String, List<String>> incoming = new LinkedHashMap<>();

    /** 发出的好友请求，key = 发起方 UUID，value = 被请求方 UUID 列表。 */
    public Map<String, List<String>> outgoing = new LinkedHashMap<>();

    /** 说说动态，最新在前。 */
    public List<Moment> moments = new ArrayList<>();

    /** 私聊会话，key = 双方 UUID 排序后拼接的线程键。 */
    public Map<String, List<ChatMessage>> chats = new LinkedHashMap<>();

    /** 世界频道最近消息（滚动保留）。 */
    public List<ChatMessage> world = new ArrayList<>();

    public static final class Profile {
        public String uuid;
        public String name;
        public String signature = "";
        public String academy = "";
        /** 当前装备的平板主题 id，默认 halolink。 */
        public String theme = SocialThemes.DEFAULT_THEME;
        /** 已通过成就解锁的非默认主题 id。 */
        public List<String> unlockedThemes = new ArrayList<>();
        public long lastSeen;

        public Profile() {
        }

        public Profile(String uuid, String name, long lastSeen) {
            this.uuid = uuid;
            this.name = name;
            this.lastSeen = lastSeen;
        }
    }

    public static final class Moment {
        public String id;
        public String author;
        public String authorName;
        public String text;
        /** 可选的缩略图 data URI；为空表示纯文字。 */
        public String image;
        public long createdAt;
        public List<String> likes = new ArrayList<>();
        public List<Comment> comments = new ArrayList<>();

        public Moment() {
        }
    }

    public static final class Comment {
        /** v0.1 JSON 未保存评论 ID；迁移时为每条旧评论生成一次稳定标识。 */
        public String id = UUID.randomUUID().toString();
        public String author;
        public String authorName;
        public String text;
        public long createdAt;

        public Comment() {
        }

        public Comment(String author, String authorName, String text, long createdAt) {
            this.author = author;
            this.authorName = authorName;
            this.text = text;
            this.createdAt = createdAt;
        }
    }

    public static final class ChatMessage {
        public String id;
        public String thread;
        public String sender;
        public String senderName;
        public String text;
        public long createdAt;

        public ChatMessage() {
        }

        public ChatMessage(String id, String thread, String sender, String senderName, String text,
                           long createdAt) {
            this.id = id;
            this.thread = thread;
            this.sender = sender;
            this.senderName = senderName;
            this.text = text;
            this.createdAt = createdAt;
        }
    }
}
