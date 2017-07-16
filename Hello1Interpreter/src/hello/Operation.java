package hello;

public class Operation {
	
	private int operandNum;
	private String name;
	private Operand operand[];
	
	public Operation(String name) {
		this.name = name;
		this.operand = null;
		operandNum = 0;
	}
	
	public Operation(String name, Operand operand[]) {
		this.name = name;
		this.operand = operand;
		operandNum = operand.length;
	}
	
	public String getName() {
		return name;
	}
	
	public Operand[] getOperand() {
		return operand;
	}
	
	public int getOperandNum() {
		return operandNum;
	}
	
}
