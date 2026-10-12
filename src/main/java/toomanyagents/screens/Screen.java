package toomanyagents.screens;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What a screen is, wherever it is: placed in the world or carried as an item.
 * An empty url means the screen is idle and has no browser. openedBy is the thread that last opened the page.
 */
public record Screen(String id, String name, String url, String openedBy) {
    static final Screen BLANK = new Screen("", "", "", "");

    static final Codec<Screen> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.optionalFieldOf("id", "").forGetter(Screen::id),
        Codec.STRING.optionalFieldOf("name", "").forGetter(Screen::name),
        Codec.STRING.optionalFieldOf("url", "").forGetter(Screen::url),
        Codec.STRING.optionalFieldOf("openedBy", "").forGetter(Screen::openedBy)
    ).apply(instance, Screen::new));

    static final StreamCodec<ByteBuf, Screen> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, Screen::id,
        ByteBufCodecs.STRING_UTF8, Screen::name,
        ByteBufCodecs.STRING_UTF8, Screen::url,
        ByteBufCodecs.STRING_UTF8, Screen::openedBy,
        Screen::new);

    Screen withId(String value) { return new Screen(value, name, url, openedBy); }
}
