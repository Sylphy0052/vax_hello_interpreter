package hello;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;

public class FileRead {
	
	private File file;
	
	public FileRead(File file) {
		this.file = file;
	}
	
	public void read() {
		try {
			BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
			
			byte[] buf = new byte[4096];
			
			int len = in.read(buf);
			
			in.close();
			
			binaryShow(buf, len);
			
			BinaryAnalyze ba = new BinaryAnalyze(buf);
			
			ba.analyze();
			
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	private void binaryShow(byte[] buf, int len) {
		
		for(int i = 0; i < len; i ++) {
			
			if(i % 16 == 0) {
				System.out.print(String.format("%07X : ", i));
			}
			
			System.out.print(String.format("%02X ", buf[i]));
			
			if(i % 16 == 15) {
				System.out.print("\n");
			}
		}
		
		
		System.out.println("\n**********************************************************\n\n");
	}

}
