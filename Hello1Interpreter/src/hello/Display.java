package hello;

public class Display {
	
	private byte[] buf;
	
	private int count;
	private int operandNum;
	private int pos;
	
	private StringBuilder sb;
	
	public Display(byte[] buf) {
		this.buf = buf;
		sb = new StringBuilder();
		count = 0;
		operandNum = 0;
		pos = 32;
	}
	
	public void setOperationName(String operationName) {
		sb.append(" " + operationName + " ");
	}
	
	public void setOperandNum(int operandNum) {
		this.operandNum = operandNum;
	}
	
	public void showFirstBinary() {
		System.out.print(String.format("%04X :  %02X ", pos - 32, buf[pos]));
		pos++;
	}
	
	public void showBinary() {
		showBinary(1);
		//System.out.print(String.format("%02X ", buf[pos - 1]));
	}
	
	public void showBinary(int num) {
		//pos --;
		for(int i = 0; i < num; i++) {
			System.out.print(String.format("%02X ", buf[pos++]));
		}
	}
	
	public void append(String str) {
		sb.append(str);
		
		if(++count != operandNum) {
			sb.append(", ");
		}
	}
	
	public void showOperation() {
		System.out.println(sb.toString());
		init();
	}
	
	private void init() {
		sb.delete(0, sb.length());
		count = 0;
	}

}
