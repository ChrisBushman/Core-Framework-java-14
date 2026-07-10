package orsc.graphics.two.SpriteArchive;

import java.util.ArrayList;
import orsc.graphics.two.SpriteArchive.Frame.LAYER;

public class Entry {

    private Frame[] frames;
    private String id;
    private TYPE type;
    private LAYER layer;

    public Entry(String id, TYPE type, LAYER layer, int framecount) {
        this.id = id;
        this.type = type;
        this.layer = layer;
        this.frames = new Frame[framecount];
    }

    public String getID() { return id; }
    public TYPE getType() { return this.type; }
    public LAYER getLayer() { return this.layer; }
    public Frame[] getFrames() { return this.frames; }

    public ArrayList getUniqueColors() {
        ArrayList colorList = new ArrayList();
        for (int f = 0; f < this.frames.length; f++) {
            int[] pixels = this.frames[f].getPixels();
            for (int p = 0; p < pixels.length; p++) {
                Integer pixel = new Integer(pixels[p]);
                if (!(colorList.indexOf(pixel) >= 0))
                    colorList.add(pixel);
            }
        }
        return colorList;
    }
    public void changeID(String id) { this.id = id; }

    public String toString() {
        return getID();
    }

    public boolean equals(Object o) {
        if (o == null)
            return false;

        if (!Entry.class.isAssignableFrom(o.getClass()))
            return false;

        Entry entry = (Entry)o;

        if (this.frames.length != entry.frames.length ||
                !this.id.equals(entry.id) ||
                this.type != entry.type ||
                this.layer != entry.layer)

            return false;

        for (int i=0; i < frames.length; ++i) {
            if (!this.frames[i].equals(entry.frames[i]))
                return false;
        }

        return true;
    }

    public Object clone() {
        Entry entry = new Entry(
                this.id,
                this.type,
                this.layer,
                this.frames.length
        );

        for (int i=0; i<this.frames.length; ++i)
            entry.frames[i] = (Frame) this.frames[i].clone();

        return entry;
    }

    public static final class TYPE {
        public static final TYPE SPRITE = new TYPE(new LAYER[]{});
        public static final TYPE PLAYER_PART = new TYPE(new LAYER[]{LAYER.HEAD_NO_SKIN, LAYER.BODY_NO_SKIN, LAYER.LEGS_NO_SKIN});
        public static final TYPE PLAYER_EQUIPPABLE_HASCOMBAT = new TYPE((LAYER[]) LAYER.VALUES.clone());
        public static final TYPE PLAYER_EQUIPPABLE_NOCOMBAT = new TYPE(new LAYER[]{LAYER.MAIN_HAND, LAYER.OFF_HAND});
        public static final TYPE NPC = new TYPE(new LAYER[]{});

        private static final TYPE[] VALUES = {SPRITE, PLAYER_PART, PLAYER_EQUIPPABLE_HASCOMBAT, PLAYER_EQUIPPABLE_NOCOMBAT, NPC};

        private LAYER[] layers;

        private TYPE(LAYER[] layers) { this.layers = layers; }

        public LAYER[] getLayers() { return this.layers; }

        public static TYPE get(int index) { return VALUES[index]; }
    }
}
