package play.libs.vfs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import play.exceptions.UnexpectedException;
import play.libs.Files;


public abstract class VirtualFile {
	public abstract String getName();
	public abstract boolean isDirectory();
	public abstract String relativePath();
	public abstract List<VirtualFile> list();
	public abstract boolean exists();
	public abstract InputStream inputstream();
	public abstract VirtualFile child (String name);
	public abstract Long lastModified();
	public abstract long length();
    
	public static VirtualFile open (String file) {
		return open (new File(file));
	}
	
	public static VirtualFile open (File file) {
		if (file.isFile() && (file.toString().endsWith(".zip") || file.toString().endsWith(".jar")))
			try {
				return new ZFile (file);
			} catch (IOException e) {
				e.printStackTrace();
				throw new RuntimeException (e);
			}
		if (file.isDirectory())
			return new FileSystemFile (file);
		else 
			return null;
	}
	
	public String contentAsString() {
	    try {
	        return Files.readContentAsString(inputstream());
	    } catch (Exception e) {
	        throw new UnexpectedException(e);
	    }
	}
	
	public byte[] content() {
        byte[] buffer = new byte[(int) length()];
        try {
            InputStream is = inputstream();
            is.read(buffer);
            is.close();
            return buffer;
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
	}
}