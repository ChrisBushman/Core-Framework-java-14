package orsc.graphics.two.SpriteArchive;

import com.openrsc.client.model.Sprite;

public class Frame {

    private int width;
    private int height;
    private int[] pixels;
    private boolean useShift;
    private int offsetX;
    private int offsetY;
    private int boundWidth;
    private int boundHeight;
	private Sprite sprite;

    public Frame(int width, int height, boolean useShift, int offsetX, int offsetY, int boundWidth, int boundHeight ) {
        this.width = width;
        this.height = height;
        this.useShift = useShift;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.boundWidth = boundWidth;
        this.boundHeight = boundHeight;
        this.pixels = new int[width * height];
		this.sprite = new Sprite(this.pixels, this.width, this.height);
		this.sprite.setRequiresShift(this.useShift);
		this.sprite.setXShift(this.offsetX);
		this.sprite.setYShift(this.offsetY);
		this.sprite.setSomething(this.boundWidth, this.boundHeight);
    }

    public boolean equals(Object o) {
        if (o == null)
            return false;

        if (!Frame.class.isAssignableFrom(o.getClass()))
            return false;

        Frame frame = (Frame)o;

        if (this.width != frame.width ||
                this.height != frame.height ||
                this.useShift != frame.useShift ||
                this.offsetX != frame.offsetX ||
                this.offsetY != frame.offsetY ||
                this.boundWidth != frame.boundWidth ||
                this.boundHeight != frame.boundHeight)

            return false;

        for (int i = 0; i < pixels.length; ++i){
            if (this.pixels[i] != frame.pixels[i])
                return false;
        }

        return true;
    }

    public int getWidth() { return this.width; }
    public int getHeight() { return this.height; }
    public int[] getPixels() { return this.pixels; }
    public Sprite getSprite() { return this.sprite; }
    public void changePixels(int[] pixels) {
        this.pixels = pixels;
    }
    public boolean getUseShift() { return this.useShift; }
    public int getOffsetX() { return this.offsetX; }
    public int getOffsetY() { return this.offsetY; }
    public int getBoundWidth() { return this.boundWidth; }
    public int getBoundHeight() { return this.boundHeight; }

    public void changeDimensions(int width, int height) {
        this.width = width;
        this.height = height;
    }
    public void changeUseShift(Boolean use) { this.useShift = use.booleanValue(); }
    public void changeOffsetX(int value) { this.offsetX = value; }
    public void changeOffsetY(int value) { this.offsetY = value; }
    public void changeBoundWidth(int value) { this.boundWidth = value; }
    public void changeBoundHeight(int value) { this.boundHeight = value; }

    public Object clone() {
        Frame frame = new Frame(
                this.width,
                this.height,
                this.useShift,
                this.offsetX,
                this.offsetY,
                this.boundWidth,
                this.boundHeight
        );

        for (int i = 0; i < this.getPixels().length; ++i)
            frame.getPixels()[i] = this.getPixels()[i];

        return frame;
    }
	public static final class LAYER {
		public static final LAYER HEAD_NO_SKIN = new LAYER(0); //can be basic head or full helm
		public static final LAYER BODY_NO_SKIN = new LAYER(1); //can be basic body or plate mail
		public static final LAYER LEGS_NO_SKIN = new LAYER(2); //can be basic legs or plate legs
		public static final LAYER MAIN_HAND = new LAYER(3);
		public static final LAYER OFF_HAND = new LAYER(4);
		public static final LAYER HEAD_WITH_SKIN = new LAYER(5); //medium helms / hats
		public static final LAYER BODY_WITH_SKIN = new LAYER(6); //chainmails
		public static final LAYER LEGS_WITH_SKIN = new LAYER(7); //robes
		public static final LAYER NECK = new LAYER(8);
		public static final LAYER BOOTS = new LAYER(9);
		public static final LAYER GLOVES = new LAYER(10);
		public static final LAYER CAPE = new LAYER(11);

		public static final LAYER[] VALUES = {
			HEAD_NO_SKIN, BODY_NO_SKIN, LEGS_NO_SKIN, MAIN_HAND, OFF_HAND,
			HEAD_WITH_SKIN, BODY_WITH_SKIN, LEGS_WITH_SKIN, NECK, BOOTS, GLOVES, CAPE
		};

		private final int index;

		private LAYER(int index) { this.index = index; }

		public int getIndex() { return index; }

		public static LAYER get(int index) { return VALUES[index]; }
	}
}
