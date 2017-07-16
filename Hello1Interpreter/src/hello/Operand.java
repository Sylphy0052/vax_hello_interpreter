package hello;

public class Operand {
	
	enum Type {
		NONE,
		BYTE,
		WORD,
		LONG,
		UNDEFINED
	}
	
	enum Relative {
		REL,
		ABS
	}
	
	private Relative rel;
	private Type type;
	
	public Operand() {
		this(Type.NONE, Relative.ABS);
	}

	public Operand(Type type) {
		this(type, Relative.ABS);
	}
	
	public Operand(Relative rel) {
		this(Type.NONE, rel);
	}
	
	public Operand(Type type, Relative rel) {
		this.type = type;
		this.rel = rel;
	}
	
	public Type getType() {
		return type;
	}
	
	public Relative getRel() {
		return rel;
	}
	
}
