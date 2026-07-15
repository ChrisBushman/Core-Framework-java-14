package orsc.graphics.two.SpriteArchive;

import java.io.File;
import orsc.util.SimpleList;


public class Subspace {
    private File home;
    private String name = "";
    private SimpleList entryList = new SimpleList();

    public String toString() { return getName(); }

    public Subspace(String name) {
        this.name = name;
    }

    public void setName(String name) { this.name = name; }
    public String getName() { return this.name; }
    public File getHome() { return this.home; }

    public SimpleList getEntryList() { return entryList; }

    public int getEntryCount() { return this.entryList.size(); }

    public int getSpriteCount() {
        int spriteCount = 0;
        { java.util.Enumeration _it = entryList.elements(); while (_it.hasMoreElements()) { Entry entry = (Entry) _it.nextElement();
            if (entry.getFrames().length == 1)
                ++spriteCount;
        }}
        return spriteCount;
    }
    public int getAnimationCount() {
        int animationCount = 0;
        { java.util.Enumeration _it2 = entryList.elements(); while (_it2.hasMoreElements()) { Entry entry = (Entry) _it2.nextElement();
            if (entry.getFrames().length > 1)
                ++animationCount;
        }}
        return animationCount;
    }
}
