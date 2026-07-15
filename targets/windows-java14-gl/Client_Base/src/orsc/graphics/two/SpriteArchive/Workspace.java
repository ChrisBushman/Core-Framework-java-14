package orsc.graphics.two.SpriteArchive;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Workspace {

    private File home;
    private String name;
    private List subspaces = new ArrayList();

    public Workspace(File home) {
        this.home = home;
        this.name = home.getName();
    }

    public Workspace() {}

    public String getName() { return this.name; }
    public void changeName(String name) { this.name = name; }
    public File getHome() { return this.home; }
    public List getSubspaces() {
        return this.subspaces;
    }

    public Subspace getSubspaceByName(String name) {
        { java.util.Iterator _it = getSubspaces().iterator(); while (_it.hasNext()) { Subspace subspace = (Subspace) _it.next();
            if (subspace.getName().equalsIgnoreCase(name))
                return subspace;
        }}

        return null;
    }

    public int getSubspaceCount() { return this.subspaces.size(); }
    public int getEntryCount() {
        int entryCount = 0;
        { java.util.Iterator _it2 = this.subspaces.iterator(); while (_it2.hasNext()) { Subspace subspace = (Subspace) _it2.next();
            entryCount += subspace.getEntryCount();
        }}
        return entryCount;
    }

    public int getSpriteCount() {
        int spriteCount = 0;
        { java.util.Iterator _it3 = this.subspaces.iterator(); while (_it3.hasNext()) { Subspace subspace = (Subspace) _it3.next();
            spriteCount += subspace.getSpriteCount();
        }}
        return spriteCount;
    }
    public int getAnimationCount() {
        int animationCount = 0;
        { java.util.Iterator _it4 = this.subspaces.iterator(); while (_it4.hasNext()) { Subspace subspace = (Subspace) _it4.next();
            animationCount += subspace.getAnimationCount();
        }}
        return animationCount;
    }
}

