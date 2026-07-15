package orsc.graphics.two.SpriteArchive;

import java.io.File;
import orsc.util.SimpleList;


public class Workspace {

    private File home;
    private String name;
    private SimpleList subspaces = new SimpleList();

    public Workspace(File home) {
        this.home = home;
        this.name = home.getName();
    }

    public Workspace() {}

    public String getName() { return this.name; }
    public void changeName(String name) { this.name = name; }
    public File getHome() { return this.home; }
    public SimpleList getSubspaces() {
        return this.subspaces;
    }

    public Subspace getSubspaceByName(String name) {
        { java.util.Enumeration _it = getSubspaces().elements(); while (_it.hasMoreElements()) { Subspace subspace = (Subspace) _it.nextElement();
            if (subspace.getName().equalsIgnoreCase(name))
                return subspace;
        }}

        return null;
    }

    public int getSubspaceCount() { return this.subspaces.size(); }
    public int getEntryCount() {
        int entryCount = 0;
        { java.util.Enumeration _it2 = this.subspaces.elements(); while (_it2.hasMoreElements()) { Subspace subspace = (Subspace) _it2.nextElement();
            entryCount += subspace.getEntryCount();
        }}
        return entryCount;
    }

    public int getSpriteCount() {
        int spriteCount = 0;
        { java.util.Enumeration _it3 = this.subspaces.elements(); while (_it3.hasMoreElements()) { Subspace subspace = (Subspace) _it3.nextElement();
            spriteCount += subspace.getSpriteCount();
        }}
        return spriteCount;
    }
    public int getAnimationCount() {
        int animationCount = 0;
        { java.util.Enumeration _it4 = this.subspaces.elements(); while (_it4.hasMoreElements()) { Subspace subspace = (Subspace) _it4.nextElement();
            animationCount += subspace.getAnimationCount();
        }}
        return animationCount;
    }
}

