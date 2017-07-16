package hello;

import java.io.File;

public class Main {

	public static void main(String[] args) {
		// TODO Auto-generated method stub
		String fileName = "/Users/Kento/fkd/vaxsetup/testset/asm/a.out";
//		String fileName = "/Users/Kento/fkd/vaxsetup/testset/c/a.out";
		
		File file = new File(fileName);
		
		FileRead fr = new FileRead(file);
		
		fr.read();
	}

}
