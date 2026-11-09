package moe.matsuri.nb4a.proxy.xhttp;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

/**
 * 黑石 (Heysocks) 官方 "xhttp" 私有协议节点.
 *
 * YAML 字段 (黑石原生):
 *   server/port       落地地址 (仅展示)
 *   gateway           鉴权网关 host:port (真正拨号目标) -> serverAddress/serverPort
 *   password          key1:key2 (AES key 仅由 key1 派生)
 *   sess              68B hex 会话前缀
 *   auth              hex 运行时令牌 (服务端校验)
 *   uuid              伪装字段
 */
public class XhttpBean extends AbstractBean {

    public String password;       // key1:key2
    public String sess;           // 68-byte hex
    public String auth;           // token hex
    public String uuid;           // decoy
    public String paddingLen;     // "8-64"
    public String landingServer;  // 落地 server (展示用)
    public Integer landingPort;   // 落地 port (展示用)

    /**
     * auth 为空时，回退到 password 里冒号后的 key2。
     *
     * 注意：key2 的格式是 [0x31 = 49 长度前缀][49 字节 token] 共 100 hex。
     * libcore 的 buildPrefix 会自己写 tokLen = byte(len(token))，所以这里必须
     * 去掉首字节，否则 tokLen 会写成 50（0x32），比服务端期望的 49 多一个字节，
     * 服务端校验直接失败 —— 表现为节点全部不可用。
     */
    public String effectiveAuth() {
        if (auth != null && !auth.isEmpty()) return auth;
        if (password != null && password.contains(":")) {
            String key2 = password.substring(password.indexOf(':') + 1);
            // 100 hex 且以 31 开头 = [0x31 长度前缀][49B token]，剥掉前缀
            if (key2.length() == 100 && key2.startsWith("31")) {
                return key2.substring(2);
            }
            return key2;
        }
        return "";
    }

    /** password 里冒号前的 key1。 */
    public String key1() {
        if (password == null) return "";
        int i = password.indexOf(':');
        return i < 0 ? password : password.substring(0, i);
    }

    public static final Creator<XhttpBean> CREATOR = new CREATOR<XhttpBean>() {
        @NonNull
        @Override
        public XhttpBean newInstance() {
            return new XhttpBean();
        }

        public XhttpBean[] newArray(int size) {
            return new XhttpBean[size];
        }
    };

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (password == null) password = "";
        if (sess == null) sess = "";
        if (auth == null) auth = "";
        if (uuid == null) uuid = "";
        if (paddingLen == null) paddingLen = "8-64";
        if (landingServer == null) landingServer = "";
        if (landingPort == null) landingPort = 0;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(password);
        output.writeString(sess);
        output.writeString(auth);
        output.writeString(uuid);
        output.writeString(paddingLen);
        output.writeString(landingServer);
        output.writeInt(landingPort);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        password = input.readString();
        sess = input.readString();
        auth = input.readString();
        uuid = input.readString();
        if (version >= 1) {
            paddingLen = input.readString();
            landingServer = input.readString();
            landingPort = input.readInt();
        } else {
            paddingLen = "8-64";
            landingServer = "";
            landingPort = 0;
        }
    }

    @NotNull
    @Override
    public XhttpBean clone() {
        return KryoConverters.deserialize(new XhttpBean(), KryoConverters.serialize(this));
    }
}
