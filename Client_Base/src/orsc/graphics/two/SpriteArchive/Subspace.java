package orsc.graphics.two.SpriteArchive;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Subspace {
    private File home;
    private String name = "";
    private List entryList = new ArrayList();

    public String toString() { return getName(); }

    public Subspace(String name) {
        this.name = name;
    }

    public void setName(String name) { this.name = name; }
    public String getName() { return this.name; }
    public File getHome() { return this.home; }

    public List getEntryList() { return entryList; }

    public int getEntryCount() { return this.entryList.size(); }

    public int getSpriteCount() {
        int spriteCount = 0;
        { java.util.Iterator _it = entryList.iterator(); while (_it.hasNext()) { Entry entry = (Entry) _it.next();
            if (entry.getFrames().length == 1)
                ++spriteCount;
        }}
        return spriteCount;
    }
    public int getAnimationCount() {
        int animationCount = 0;
        { java.util.Iterator _it2 = entryList.iterator(); while (_it2.hasNext()) { Entry entry = (Entry) _it2.next();
            if (entry.getFrames().length > 1)
                ++animationCount;
        }}
        return animationCount;
    }
}
