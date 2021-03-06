package ve3.hdw;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import ve3.disassm.Dump;
import ve3.disassm.V32Disassm;
import ve3.disassm.V32Disassm.OT;
import ve3.disassm.V32Disassm.Ope;
import ve3.main.Context;
import ve3.os.FSystem;
import ve3.os.MetaInfo;
import ve3.os.OpInfo;
import ve3.os.OpInfo.OpInfoSub;
import ve3.os.OpInfo.Type;
import ve3.os.Unix32V;
import ve3.util.BCDUtil;

public class Cpu implements Cloneable {
	
	public static final int r0  = 0; 
	public static final int r1  = 1; 
	public static final int r2  = 2; 
	public static final int r3  = 3; 
	public static final int r4  = 4; 
	public static final int r5  = 5; 
	public static final int r6  = 6; 
	public static final int r7  = 7; 
	public static final int r8  = 8; 
	public static final int r9  = 9; 
	public static final int r10 = 10; 
	public static final int r11 = 11; 
	public static final int ap  = 12; 
	public static final int fp  = 13; 
	public static final int sp  = 14; 
	public static final int pc  = 15;
	
	public static final int PSW_C = 0x1;
	public static final int PSW_V = 0x2;
	public static final int PSW_Z = 0x4;
	public static final int PSW_N = 0x8;
	
	
	private int[] reg = new int[16];
	private int psl;
	private Memory memory;
	private OpInfo opinfo;
	
	private Unix32V os;
	private ByteArrayOutputStream logOut;
	private PrintStream log;
	//private ByteArrayOutputStream symOut;
	//private PrintStream sym;
	private Context ctx;
	
	private boolean debug;
	private final byte[] space = "  ".getBytes();
	
	// for computation (e.g., carry, ov flags)
	private long  val64;
	private int   val32;
	private short val16;
	private byte  val8;
	
	// for debug
	private Map<Integer, String> symTable;
	private Stack<String> callStack;
	public int stepCount = 0; // public for temporary
	
	public static Ope[] table = new Ope[0xfffff];
	
	static {
		for (Ope ope : Ope.values()) {
			table[ope.mne] = ope;
		}
	}
	
	public Cpu() {
		logOut = new ByteArrayOutputStream();
		log = new PrintStream(logOut);
		debug = false;
		//symOut = new ByteArrayOutputStream();
		//sym = new PrintStream(symOut);
		init();
	}
			
	public void setMemory(Memory memory) {
		this.memory = memory;
	}
	
	public void init() {
		for (int i = 0; i < reg.length; ++i) {
			reg[i] = 0;
		}
		opinfo = new OpInfo();	
		psl = 0x41f0000;		
		callStack = new Stack<String>();
	}
	
	public void setSymTable(Map<Integer, String> symTable) {
		this.symTable = symTable;
	}
	
	public void setDebug(boolean debug) {
		this.debug = debug;
	}
	
	public void setPc(int pc) {
		memory.setPc(this.reg[this.pc] = pc);		
	}
	
	public void setSp(int val) {
		reg[sp] = val;
	}
	
	public void setOs(Unix32V os) {
		this.os = os;
	}
	
	public void setContext(Context ctx) {
		this.ctx = ctx;
	}
	
	public int[] getRegister() {
		return reg;
	}
	
	public MVal fetch() {
		MVal mval = memory.fetch();
		reg[pc] = mval.pc;
		return mval;
	}
	public MVal fetch2() {
		MVal mval = memory.fetch2();
		reg[pc] = mval.pc;
		System.out.printf("reg[pc] = %X\n", mval.pc);
		return mval;
	}
	public MVal fetch4() {
		MVal mval = memory.fetch4();
		reg[pc] = mval.pc;
		return mval;
	}
	public MVal fetch8() {
		MVal mval = memory.fetch8();
		reg[pc] = mval.pc;
		return mval;
	}
	
	private long fetch(OT optype) {
		switch(optype) {
		case df:
		case q: {
			return fetch8().lval;
		}
		case f:
		case l: {
			return fetch4().ival;			
		}
		case w: {
			return fetch2().sval;
		}
		case b: 
		case vb: {
			return fetch().bval;
		}
		default: {
			System.out.println("unrecognised optype(OT) in fetch");
			System.exit(1);
		}
		}
		
		return 0;
	}
	
	private OpInfoSub resolveDispPc(OT optype, byte type) {

		switch(type) {
		case 0x8: { // Immediate OK
			opinfo.opsub.type = Type.Immed;
			opinfo.opsub.arg = fetch(optype);			
			return opinfo.opsub;
		}
		case 0x9: { // Absolute
			opinfo.opsub.type = Type.Abs;
			opinfo.opsub.arg = fetch4().ival;
			System.out.println(type + " not implemented yet in resolveDispPc");
			System.exit(1);
			return opinfo.opsub;
		}
		case 0xc: { // word relative
			opinfo.opsub.type = Type.WordRel;
			MVal mval = fetch2();
			opinfo.opsub.arg = mval.sval + mval.pc;
			opinfo.opsub.addr = opinfo.opsub.arg;
			return opinfo.opsub;
		}
		case 0xd: { // word relative deferred
			opinfo.opsub.type = Type.WordRelDefer;
			MVal mval = fetch2();
			opinfo.opsub.arg = mval.sval + mval.pc;
			opinfo.opsub.addr = memory.readInt((int)opinfo.opsub.arg);
			return opinfo.opsub;
		}
		case 0xe: { // long relative
			opinfo.opsub.type = Type.LongRel;
			MVal mval = fetch4();
			opinfo.opsub.addr = opinfo.opsub.arg = mval.ival + mval.pc;			
			return opinfo.opsub;
		}
		case 0xf: { // long relative deferred
			opinfo.opsub.type = Type.LongRelDefer;
			MVal mval = fetch4();
			opinfo.opsub.arg = mval.ival + mval.pc;		
			//System.out.printf("long relative = %x\n", opinfo.opsub.arg);
			//memory.dump((int)opinfo.opsub.arg, 4);
			opinfo.opsub.addr = memory.readInt((int)opinfo.opsub.arg);
			//System.out.printf("long relative = %x\n", opinfo.opsub.addr);			
			return opinfo.opsub;
		}
		default: {
			System.out.println("unsupported byte in program couter address mode in resolveDispPc: " + type);
			System.exit(1);
		}
		}		
		return null;		
	}

	
	

	
	
	private OpInfoSub resolveDisp(OT optype) {
		System.out.printf("resolveDisp(%S)\n", optype.toString());
		if (optype == OT.Brb) {
			MVal mval = fetch();
			opinfo.opsub.type = Type.Branch1;
			opinfo.opsub.arg = mval.bval + mval.pc;
			opinfo.opsub.addr = opinfo.opsub.arg;
			return opinfo.opsub;
		} else if (optype == OT.Brw) {
			MVal mval = fetch2();
			opinfo.opsub.type = Type.Branch2;
			opinfo.opsub.arg = mval.sval + mval.pc;
			opinfo.opsub.addr = opinfo.opsub.arg;
			return opinfo.opsub;
		}
		
		byte arg = fetch().bval;
		System.out.printf("arg : %X\n", arg);
		byte type = (byte)((arg >> 4) & 0xf);
		byte value = (byte)(arg & 0xf);
		
		System.out.printf("type : %X\n", type);
		// set pointer for bitfield type
		switch(type) {
		case 0:
		case 1:
		case 2:
		case 3: {
			opinfo.opsub.pos = reg[pc] - 1; // for immediate
		}
		default: {
			opinfo.opsub.pos = reg[pc]; // 
		}
		}
		
		
		switch (type) {
		case 0:
		case 1:
		case 2:
		case 3: { // Literal
			opinfo.opsub.type = Type.Literal;
			opinfo.opsub.operand = (byte)(arg & 0x3f);
			opinfo.opsub.arg = opinfo.opsub.operand;
			return opinfo.opsub;				
		}
		case 4: { // Index
			opinfo.opsub.type = Type.Index;
			opinfo.opsub.operand = (byte)(arg & 0x3f);
			long regaddr = (long)(reg[opinfo.opsub.operand] & 0xffffffffL);
			//System.out.printf("index reg[%d] = %x\n", opinfo.opsub.operand, regaddr);
			switch (optype) {
			case b: 
			case vb: {				
				break;
			}
			case w: {
				regaddr *= 2;
				break;
			}
			case l:
			case f: {
				regaddr *= 4;
				break;
			}
			default: {
				System.out.println("unsupported OT in resolveDisp for Index Mode");
				System.exit(1);
				break;
			}
			}			
			opinfo.opsub.iaddr = regaddr;			
			return opinfo.opsub;
		}
		case 5: { // Register
			opinfo.opsub.type = Type.Register;
			opinfo.opsub.operand = (byte)(arg & 0xf);
			opinfo.opsub.addr = opinfo.opsub.operand;
			return opinfo.opsub;				
		}
		case 6: { // Register Defered 
			opinfo.opsub.type = Type.RegDefer;
			opinfo.opsub.operand = (byte)(arg & 0xf);
			//opinfo.opsub.addr = (long)reg[opinfo.opsub.operand = (byte)(arg & 0xf)] & 0xffffffffL;
			opinfo.opsub.addr = (long)reg[opinfo.opsub.operand] & 0xffffffffL;
			return opinfo.opsub;
		}
		case 7: { // Auto Decrement
			opinfo.opsub.type = Type.AutoDec;
			opinfo.opsub.operand = (byte)(arg & 0xf);
			
			switch (optype) {
			case b: 
			case vb: {
				reg[opinfo.opsub.operand] -= 1;
				break;				
			}
			case w: {
				reg[opinfo.opsub.operand] -= 2;
				break;
			}
			case f:
			case l: { 
				reg[opinfo.opsub.operand] -= 4;
				break;
			}			
			default: {
				System.out.println("unsupported optype in AutoDecrement in resolveDisp : " + optype);
				System.out.printf("reg[pc] = %x, stepCount = %d\n", reg[pc], stepCount);
				System.exit(1);
			}
			}
			opinfo.opsub.addr = reg[opinfo.opsub.operand];				
			return opinfo.opsub;		
		}
		case 8: { // Auto Increment
			if (value == 0xf) return resolveDispPc(optype, type);
			opinfo.opsub.type = Type.AutoInc;
			opinfo.opsub.operand = (byte)(arg & 0xf);
			opinfo.opsub.addr = reg[opinfo.opsub.operand];
			switch (optype) {
			case b: 
			case vb: {
				reg[opinfo.opsub.operand] += 1;
				break;
			}
			case w: {
				reg[opinfo.opsub.operand] += 2;
				break;
			}
			case f:
			case l: {
				reg[opinfo.opsub.operand] += 4;
				break;
			}
			default: {
				System.out.println("unsupported optype in AutoIncrement in resolveDisp");
				System.exit(1);
			}
			}
			return opinfo.opsub;				
		}
		case 9: { // AutoIncDefer
			if (value == 0xf) return resolveDispPc(optype, type);
			opinfo.opsub.type = Type.AutoIncDefer;
			opinfo.opsub.operand = (byte)(arg & 0xf);
			System.out.println(type + " not implemented yet in resolveDisp");
			System.exit(1);
			return opinfo.opsub;			
		}
		case 0xa: { // Byte Displacement
			if (value == 0xf) return resolveDispPc(optype, type);
			System.out.println("Byte Displacement");
			opinfo.opsub.type = Type.ByteDisp;
			opinfo.opsub.operand = (byte)(arg & 0xf);
			opinfo.opsub.arg = fetch().bval;
			System.out.printf("addr = reg[%X] + arg(%X)\n", opinfo.opsub.operand, opinfo.opsub.arg);
			opinfo.opsub.addr = (long)reg[opinfo.opsub.operand] + (long)opinfo.opsub.arg; // minus OK
			System.out.printf("addr : %X\n", opinfo.opsub.addr);
			/*
			System.out.printf("arg = %x, %d\n", opinfo.opsub.arg, opinfo.opsub.arg);
			System.out.printf("reg = %x, %d\n", reg[opinfo.opsub.operand], reg[opinfo.opsub.operand]); 						
			System.out.printf("addr = 0x%08x\n", opinfo.opsub.addr);
			*/
			return opinfo.opsub;				
		}
		case 0xb: { // Byte Displacement Deferred
			if (value == 0xf) return resolveDispPc(optype, type);
			opinfo.opsub.type = Type.ByteDispDefer;
			opinfo.opsub.operand = (byte)(arg & 0xf);
			opinfo.opsub.arg = fetch().bval;			
			long tmp = (long)reg[opinfo.opsub.operand] + (long)opinfo.opsub.arg; 
			//System.out.printf("tmp = %x\n", tmp);
			opinfo.opsub.addr = memory.readInt((int)tmp);
			//System.out.printf("0x%x\n", opinfo.opsub.addr);
			//System.out.println(type + " not implemented yet in resolveDisp");
			//System.exit(1);
			return opinfo.opsub;
		}
		case 0x0c: { // Word Displacement
			if (value == 0xf) return resolveDispPc(optype, type);
			opinfo.opsub.type = Type.WordDisp;
			opinfo.opsub.operand = (byte)(arg & 0xf);
			opinfo.opsub.arg = fetch2().sval;
			opinfo.opsub.addr = (long)reg[opinfo.opsub.operand] + (long)opinfo.opsub.arg;
			return opinfo.opsub;
		}
		case 0x0d: { // Word Displacement Deferred
			if (value == 0xf) return resolveDispPc(optype, type);
			opinfo.opsub.type = Type.WordDispDefer;
			opinfo.opsub.operand = (byte)(arg & 0xf);
			opinfo.opsub.arg = fetch2().sval;			
			long tmp = (long)reg[opinfo.opsub.operand] + (long)opinfo.opsub.arg;
			//System.out.printf("tmp = %x\n", tmp);
			//System.out.println(type + " not implemented yet in resolveDisp");
			opinfo.opsub.addr = memory.readInt((int)tmp);
			//System.out.printf("addr = %x\n", opinfo.opsub.addr);
			//System.exit(1);
			return opinfo.opsub;
		}
		case 0x0e: { // Long Displacement
			if (value == 0xf) return resolveDispPc(optype, type);
			opinfo.opsub.type = Type.LongDisp;
			opinfo.opsub.operand = (byte)(arg & 0xf);
			opinfo.opsub.arg = fetch4().ival;
			opinfo.opsub.addr = (long)reg[opinfo.opsub.operand] + (long)opinfo.opsub.arg;
			//System.out.printf("arg = %x\n", opinfo.opsub.addr);			
			return opinfo.opsub;
		}
		case 0x0f: { // Long Displacement Deferred
			if (value == 0xf) return resolveDispPc(optype, type);
			opinfo.opsub.type = Type.LongDispDefer;
			opinfo.opsub.operand = (byte)(arg & 0xf);
			opinfo.opsub.arg = fetch4().ival;
			System.out.println(type + " not implemented yet in resolveDisp");
			System.exit(1);
			return opinfo.opsub;
		}
		default: { // index
			System.out.printf("%x is not implemented yet in resolveDisp\n", type);
			System.exit(1);				
			break;
		}
		
		}			
		return null;
	}

	private void setArg1(MetaInfo minfo) {
		OpInfoSub opsub = null;
		System.out.println("setArg1");
		while ((opsub = resolveDisp(minfo.arg1)).type == Type.Index) {
			opinfo.pushIdx1(opsub.operand);
			System.out.printf("push : %X\n", opsub.operand);
		}		
		opinfo.setType1(opsub.type);
		System.out.printf("type : %S\n", opsub.type.toString());
		opinfo.setOpe1(opsub.operand);
		System.out.printf("operand : %X\n", opsub.operand);
		opinfo.setArg1(opsub.arg);
		System.out.printf("arg : %d\n", opsub.arg);
		opinfo.setAddr1(opsub.addr);
		System.out.printf("addr : %d\n", opsub.addr);
		opinfo.setPos1(opsub.pos);
		System.out.printf("pos : %d\n", opsub.pos);
		opinfo.setIAddr1(opsub.iaddr);
		System.out.printf("iaddr : %d\n", opsub.iaddr);
		opsub.clear();
	}
	
	private void setArg2(MetaInfo minfo) {
		setArg1(minfo);		
		System.out.printf("setArg2\n");
		OpInfoSub opsub = null;
		while ((opsub = resolveDisp(minfo.arg2)).type == Type.Index) {
			opinfo.pushIdx2(opsub.operand);
		}		
		opinfo.setType2(opsub.type);
		opinfo.setOpe2(opsub.operand);
		opinfo.setArg2(opsub.arg);
		opinfo.setAddr2(opsub.addr);
		opinfo.setPos2(opsub.pos);
		opinfo.setIAddr2(opsub.iaddr);
		opsub.clear();
	}
	
	private void setArg3(MetaInfo minfo) {
		setArg2(minfo);
		OpInfoSub opsub = null;
		while ((opsub = resolveDisp(minfo.arg3)).type == Type.Index) {
			opinfo.pushIdx3(opsub.operand);
		}
		
		opinfo.setType3(opsub.type);
		opinfo.setOpe3(opsub.operand);
		opinfo.setArg3(opsub.arg);
		opinfo.setAddr3(opsub.addr);
		opinfo.setPos3(opsub.pos);
		opinfo.setIAddr3(opsub.iaddr);
		opsub.clear();
	}
	
	private void setArg4(MetaInfo minfo) {
		setArg3(minfo);
		OpInfoSub opsub = null;
		while ((opsub = resolveDisp(minfo.arg4)).type == Type.Index) {
			opinfo.pushIdx4(opsub.operand);
		}
		opinfo.setType4(opsub.type);
		opinfo.setOpe4(opsub.operand);
		opinfo.setArg4(opsub.arg);
		opinfo.setAddr4(opsub.addr);
		opinfo.setPos4(opsub.pos);
		opinfo.setIAddr4(opsub.iaddr);
		opsub.clear();
	}
	
	private byte getByte(Type type, long arg, long addr) {
		return (byte)getInt(type, arg, addr);		
	}
	private short getShort(Type type, long arg, long addr) {
		return (short)getInt(type, arg, addr);		
	}
	
	// for bitfield access
	private int getIntV(Type type, long arg, long addr, long offset, long pos) {
		switch (type) {
		case Immed: {			
			//System.out.printf("pos = %x\n", pos);
			long data = memory.readLong((int)pos);
			//System.out.printf("data = %016x\n", data);
			int r = (int)((data >> offset) & 0xffffffffL);			
			//System.out.printf("r = %016x\n", r);
			return r;			
		}
		case ByteDisp: {
			long data = memory.readLong((int)addr);
			int r = (int)((data >> offset) & 0xffffffffL);
			return r;
		}
		case WordRel: {
			//System.out.printf("r0 = %x\n", reg[r0]);
			//System.out.printf("addr = %x\n", addr);						
			long data = memory.readLong((int)addr);
			//memory.dump((int)addr, 32);			
			int r = (int)((data >> offset) & 0xffffffffL);
			//System.out.printf("r = %x\n", r);
			return r;
		}
		case LongRel: {
			long data = memory.readLong((int)addr);
			int r = (int)((data >> offset) & 0xffffffffL);
			return r;
		}
		case Register: {
			int v1 = reg[(int)addr];
			int next = (addr == pc) ? 0 : (int)(addr + 1);
			long data = (long)((long)reg[next] << 32 | v1);
			int r = (int)((data >> offset) & 0xffffffffL);			
			return r;
		}
		case RegDefer: {
			long data = memory.readLong((int)addr);
			//System.out.printf("data = %016x\n", data);
			int r = (int)((data >> offset) & 0xffffffffL);
			//System.out.printf("r   = %016x\n", r);
			return r;
		}
		default: {
			System.out.println("unrecognized type in getIntV: " + type);
			System.exit(1);
		}
		}
		return 0;
	}
	
	private int getInt(Type type, long arg, long addr) {
		switch (type) {
		case Branch1: {
			return (int)addr;
		}
		case Literal: 
		case Immed: {
			return (int)arg;
		}
		case Register: {
			return reg[(int)addr];
		}
		case ByteDisp: {
			System.out.println("ByteDisp");
			//System.out.printf("%x\n", (int)addr);
			//int val = memory.readInt((int)addr);
			//System.out.println("val = " + val);
			System.out.printf("memory.readInt(%X)\n", (int)addr);
			return memory.readInt((int)addr);
		}
		case RegDefer: {
			return memory.readInt((int)addr);
		}
		case ByteDispDefer: {
			return memory.readInt((int)addr);
		}
		case AutoInc: {
			//System.out.printf("%x\n", (int)addr);
			return memory.readInt((int)addr);			
		}
		case LongRel: {
			return memory.readInt((int)addr);
		}
		case LongRelDefer: {
			return  memory.readInt((int)addr);
		}
		case WordRel: {
			return memory.readInt((int)addr);			
		}
		case AutoDec: {
			return memory.readInt((int)addr);
		}
		case WordRelDefer: {
			return memory.readInt((int)addr);
		}
		case WordDisp: {
			return memory.readInt((int)addr);			
		}
		case LongDisp: {
			return memory.readInt((int)addr);
		}		
		default: {
			System.out.println("unrecognized type in getInt: " + type);
			System.out.printf("reg[pc] = %x, stepCount = %d\n", reg[pc], stepCount);
			System.exit(1);
		}
		}
		
		return 0;
	}
	
	private void storeShort(Type type, long addr, short value) {
		switch(type) {
		case Register: {
			reg[(int)addr] = (reg[(int)addr] & 0xffff0000) | (value & 0xffff);
			break;
		}
		case RegDefer: {
			memory.writeShort((int)addr, value);
			break;
		}		
		case LongRel: {
			memory.writeShort((int)addr, value);
			break;
		}
		case WordDisp: {			
			memory.writeShort((int)addr, value);			
			break;
		}		
		case ByteDisp: {
			memory.writeShort((int)addr, value);
			break;
		}
		case LongDisp: {
			memory.writeShort((int)addr, value);
			break;
		}
		default: {
			System.out.printf("addr = 0x%x, value = 0x%x\n", addr, value);
			System.out.println("unrecognized type in storeShort: " + type);
			System.out.printf("reg[pc] = %x, stepCount = %d\n", reg[pc], stepCount);
			System.exit(1);
		}
		}
		
	}
	
	private void storeByte(Type type, long addr, byte value) {
		switch (type) {
		case Register: {
			//System.out.printf("regnum = %x, value = %x\n", addr, value);
			//reg[(int)addr] |= (value & 0xff); // mistake!!
			reg[(int)addr] = (reg[(int)addr] & 0xffffff00) | (value & 0xff);
			break;
		}
		case ByteDisp: {
			memory.writeByte((int)addr, value);
			break;
		}
		case LongRel: {
			memory.writeByte((int)addr, value);
			break;
		}
		case AutoInc: {
			memory.writeByte((int)addr, value);
			break;
		}
		case RegDefer: {			
			memory.writeByte((int)addr, value);
			break;
		}
		case WordRel: {
			memory.writeByte((int)addr, value);
			break;
		}
		case WordRelDefer: {
			memory.writeByte((int)addr, value);
			break;
		}
		case AutoDec: {
			memory.writeByte((int)addr, value);
			break;
		}
		case LongRelDefer: {
			memory.writeByte((int)addr, value);
			break;
		}
		case WordDispDefer: {						
			memory.writeByte((int)addr, value);
			break;
		}
		case WordDisp: {
			memory.writeByte((int)addr, value);
			break;
		}
		default: {
			System.out.printf("addr = 0x%x, value = 0x%x\n", addr, value);
			System.out.println("unrecognized type in storeByte: " + type);
			System.out.printf("reg[pc] = %x, stepCount = %d\n", reg[pc], stepCount);
			System.exit(1);
		}
		
		}
	}
	
	private void storeIntV(Type type, long addr, int value, int offset) {
		switch (type) {
		case Register: {			
			reg[(int)(addr & 0xff)] = value;
			break;
		}
		case ByteDisp: {
			memory.writeInt((int)addr, value);
			break;
		}
		default: {
			System.out.printf("addr = 0x%x, value = 0x%x\n", addr, value);
			System.out.println("unrecognized type in storeIntV: " + type);
			System.out.printf("reg[pc] = %x, stepCount = %d\n", reg[pc], stepCount);
			System.exit(1);
		}
		}
	}
	
	private void storeInt(Type type, long addr, int value) {
		System.out.printf("storeInt(%S, %X, %X)\n", type.toString(), addr, value);
		switch (type) {
		case Register: {
			System.out.printf("reg[%X]\n", addr & 0xFF);
			reg[(int)(addr & 0xff)] = value;
			break;
		}
		case RegDefer: {
			memory.writeInt((int)addr, value);			
			break;
		}
		case ByteDisp: {
			System.out.printf("memory.writeInt(%d, %d)\n", (int)addr, value);
			memory.writeInt((int)addr, value);
			break;
		}
		case LongRel: {
			memory.writeInt((int)addr, value);			
			break;
		}
		case AutoDec: {
			memory.writeInt((int)addr, value);
			break;
		}
		case LongRelDefer: {
			memory.writeInt((int)addr, value);
			break;
		}
		case ByteDispDefer: {
			memory.writeInt((int)addr, value);
			break;
		}
		case WordRel: {
			memory.writeInt((int)addr, value);
			break;
		}
		case WordRelDefer: {
			memory.writeInt((int)addr, value);
			break;
		}
		case LongDisp: {
			memory.writeInt((int)addr, value);
			break;
		}
		case WordDisp: {
			memory.writeInt((int)addr, value);
			break;
		}
		case AutoInc: {
			memory.writeInt((int)addr, value);
			break;
		}
		case WordDispDefer: {			
			memory.writeInt((int)addr, value);					
			break;
		}
		default: {
			System.out.printf("addr = 0x%x, value = 0x%x\n", addr, value);
			System.out.println("unrecognized type in storeInt: " + type);
			System.out.printf("reg[pc] = %x, stepCount = %d\n", reg[pc], stepCount);			
			System.exit(1);
		}
		}
	}
	
	private void storeRawData(byte[] rawdata, int offset) {
		memory.rawWrite(rawdata, 0, offset, rawdata.length);
	}

	// quodward
	private void storeLong(Type type, long addr, long value) {
		switch (type) {
		case Register: {
			reg[(int)addr]     = (int)value;
			reg[(int)addr + 1] = (int)(value >> 32);
			break;
		}
		default: {
			System.out.printf("addr = 0x%x, value = 0x%x\n", addr, value);
			System.out.println("unrecognized type in storeLong: " + type);
			System.exit(1);
		}
		}
	}
		
	
	public void showHeader() {
		System.out.println("   r0       r1       r2       r3       r4       r5       r6       r7       r8       r9      r10    "
				+ "  r11       ap       fp       sp       pc    NZVC");
	}
	
	private void storeRegInfo() {		
		log.printf("%08x %08x %08x %08x %08x %08x %08x %08x %08x %08x %08x %08x %08x %08x %08x %08x", 
				reg[r0], reg[r1], reg[r2], reg[r3], reg[r4], reg[r5], reg[r6], reg[r7], reg[r8], reg[r9], 
				reg[r10], reg[r11], reg[ap], reg[fp], reg[sp], reg[pc]);
		
		// NZCV
		log.printf(" %s%s%s%s", isN() ? "N" : "-",
				isZ() ? "Z" : "-",
				isV() ? "V" : "-",
				isC() ? "C" : "-"
			);		
	}
	
	private boolean isN() {
		return (((psl & PSW_N) >>> 3) & 1) == 1; 
	}
	private boolean isZ() {
		return (((psl & PSW_Z) >>> 2) & 1) == 1;
	}
	private boolean isV() {
		return (((psl & PSW_V) >>> 1) & 1) == 1;
	}
	private boolean isC() {
		return ((psl & PSW_C) & 1) == 1;
	}
	
	private void setNZVC(boolean nf, boolean zf, boolean vf, boolean cf) {
		psl = nf ? (psl | PSW_N) : (psl & ~PSW_N);
		psl = zf ? (psl | PSW_Z) : (psl & ~PSW_Z);
		psl = vf ? (psl | PSW_V) : (psl & ~PSW_V);
		psl = cf ? (psl | PSW_C) : (psl & ~PSW_C);		
	}
	/* for OS system call*/
	public void clearCarry() {
		psl &= ~PSW_C;
	}
	
	private void clearZero() {
		psl &= ~PSW_Z;
	}
	public void setCarry() {
		psl |= PSW_C;
	}
	
	public void pushInt(int val) {
		reg[sp] -= 4;
		memory.writeInt(reg[sp], val);
		//memory.dump(reg[sp], 4);
	}
	
	public int popInt() {
		int r = memory.readInt(reg[sp]);
		reg[sp] += 4;
		return r;
	}
	
	
	private void storeDisInfo(OpInfo opinfo, String opname) {
		try {
			//log.write("  ".getBytes());
			log.write(space);
			log.write(Dump.dump(opinfo, opname).getBytes());
		} catch (IOException e) {
			
		}
	}
			
	public void start() {
		//if (debug) {
		if (debug && !ctx.hasParent()) {			
			showHeader();
		}
		//memory.dump(0xc00, 16);		

		if (symTable != null && symTable.containsKey(reg[pc] - 2)) {
			log.println(callStack.push(symTable.get(reg[pc] - 2)));			
		} 
		
		//int limit = 855; // end of child process
		//int limit = 273243;
		int limit = 4000000;
		
		
		
		if (ctx.hasParent()) {
			limit = 50000000; // start to exece
		}
		

		for (int i = 0; i < limit; ++i, ++stepCount) { // as 140000
			
			System.out.println("\n\n***");
			
			//FSystem.check();
			if (run() == -1) return;
			
			System.out.println("\n***\n");
		}
		System.out.println("end of loop");
		
	
	}
	
	public int run() {
		opinfo.clear();
		if (debug) {
			//log.printf("%x:", reg[pc]);
			if (symTable != null && symTable.containsKey(reg[pc] - 2)) {
				//log.println(symTable.get(reg[pc] - 2));				
				//log.println(callStack.push(symTable.get(reg[pc] - 2)));				
			} else {
				//sym.printf("0x%x", reg[pc] - 2);
				//log.println(callStack.push(new String(symOut.toByteArray())));;
				//symOut.reset();
			}
			storeRegInfo();
		}
		
		int b1 = opinfo.setOpCode(fetch().bval & 0xff);
		Ope ope = Ope.table[b1];
		//System.out.printf("b1 = %x\n", b1);
		
		if (ope == null) {
			throw new RuntimeException();
		}
		opinfo.minfo = ope.minfo;
		System.out.printf("size : %d\n", ope.minfo.size);
		switch (ope.minfo.size) {
		case 0: {
			break;
		}
		case 1: {
			setArg1(ope.minfo);
			break;
		}
		case 2: {
			setArg2(ope.minfo);
			break;
		}
		case 3: {
			setArg3(ope.minfo);
			break;
		}
		case 4: {
			setArg4(opinfo.minfo);
			break;
		}
		default: {
			System.out.printf("0x%x unrecognised size in run\n", ope.minfo.size);			
			System.exit(1);
		}
		}
		if (debug) {
			storeDisInfo(opinfo, ope.opname);
		}
		
		
		switch (ope.mne) {
		case 0: { // Halt
			break;
		}
		case 0xb0: { // movw
			short src = getShort(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			storeShort(opinfo.getType2(), opinfo.getAddr2(), src);
			setNZVC(src < 0, src == 0, false, isC());			
			break;
		}
		case 0xd0: { // movl
			System.out.println("\nMOVL");
			System.out.printf("src = getInt(%S, %d, %X)\n", opinfo.getType1().toString(), opinfo.getArg1(), opinfo.getAddr1());
			int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			System.out.printf("movl src : %X\n", src);
			//System.out.printf("src = %x\n", src);
			System.out.printf("movl storeInt(%S, %X, %X)\n", opinfo.getType2().toString(), opinfo.getAddr2(), src);
			storeInt(opinfo.getType2(), opinfo.getAddr2(), src);
			setNZVC(src < 0, src == 0, false, isC());			
			break;
		}
		case 0x90: { // movb
			val8 = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			//System.out.printf("src = %x, addr = %x, dst_addr = %x\n", val8, opinfo.getAddr1(), opinfo.getAddr2());
			storeByte(opinfo.getType2(), opinfo.getAddr2(), val8);		
			setNZVC(val8 < 0, val8 == 0, false, isC());			
			break;
		}
		case 0xbc: { // chmk CHMKではフラグはいじらない(REI命令で戻されるから)．
			int src = getShort(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			if (debug) {
				System.out.println(new String(logOut.toByteArray()));
				logOut.reset();
			}
			return os.syscall(src);			
			//break;
		}
		case 0xdd: { // pushl
			int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			pushInt(src);
			setNZVC(src < 0, src == 0, false, isC());						
			break;
		}
		case 0xc2: { // subl2
			int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int dst = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			System.out.printf("\nsubl2 src : %X\n", src);
			System.out.printf("subl2 dst : %X\n", dst);
			val64 = (long)dst - (long)src;
			val32 = (int)val64;
			System.out.printf("storeInt(%S, %X, %X)\n", opinfo.getType2().toString(), opinfo.getAddr2(), val32);
			storeInt(opinfo.getType2(), opinfo.getAddr2(), val32);
			setNZVC(val32 < 0, val32 == 0, val64 != val32, (dst & 0xffffffffL) < (src & 0xffffffffL));
			break;
		}
		case 0xc3: { // subl3
			int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int min = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			//System.out.printf("src = %x, min = %x, addr = %x\n", src, min, opinfo.getAddr3());
			val64 = (long)min - (long)src;
			val32 = (int)val64;
			storeInt(opinfo.getType3(), opinfo.getAddr3(), val32);
			setNZVC(val32 < 0, val32 == 0, val64 != val32, (min & 0xffffffffL) < (src & 0xffffffffL));						
			break;
		}
		case 0xb1: { // cmpw
			short src1 = getShort(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			short src2 = getShort(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			//System.out.printf("src1 = %x, src2 = %x\n", src1, src2);
			//System.out.printf("src1 = %d, src2 = %d\n", src1, src2);
			setNZVC(src1 < src2, src1 == src2, false, (src1 & 0xffff) < (src2 & 0xffff));			
			break;
		}
		case 0xd1: { // cmpl
			int src1 = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int src2 = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			//val64 = (long)src1 - (long)src2;
			//val32 = (int)val64;
			/*
			System.out.println("type2 = " + opinfo.getType2());
			memory.dump((int)opinfo.getAddr2(), 4);
			System.out.printf("src1 = %x, src2 = %x\n", src1, src2);
			*/
			setNZVC(src1 < src2, src1 == src2, false, (src1 & 0xffffffffL) < (src2 & 0xffffffffL));
			break;
			
		}
		case 0x91: { //cmpb
			byte src1 = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			byte src2 = getByte(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			//System.out.printf("src1 = %x, src2 = %x\n", src1, src2);
			//val8 = (byte)(src1 - src2);
			//val32 = (int)src1 - (int)src2;
			//val8 = (byte)val32;
			
			setNZVC(src1 < src2, src1 == src2, false, (src1 & 0xff) < (src2 & 0xff));			
			break;
		}
		case 0x9e: { // movab
			System.out.println("\nMOVAB\n");
			//int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1(), opinfo.minfo.arg1);
			int src = (int)opinfo.getAddr1();
			System.out.printf("src : %X\n", src);
			//System.out.printf("addr = %x\n", src);
			storeInt(opinfo.getType2(), opinfo.getAddr2(), src);
			System.out.printf("storeInt(%S, %X, %X)\n", opinfo.getType2().toString(), opinfo.getAddr2(), src);
			setNZVC(src < 0, src == 0, false, isC());			
			break;
		}
		case 0x3e: { // movaw
			int src = (int)opinfo.getAddr1();
			storeInt(opinfo.getType2(), opinfo.getAddr2(), src);
			setNZVC(src < 0, src == 0, false, isC());			
			break;
		}
		case 0xde: { // moval
			int src = (int)opinfo.getAddr1();
			//System.out.printf("src = %x\n", src);
			//System.out.printf("dst = %x\n", opinfo.getAddr2());
			storeInt(opinfo.getType2(), opinfo.getAddr2(), src);
			setNZVC(src < 0, src == 0, false, isC());
			
			break;
		}
		case 0x95: { // tstb
			int src = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			//System.out.printf("src = %x, addr = %x\n", src, opinfo.getAddr1());
			val8 = (byte)(src - 0);
			setNZVC(val8 < 0, val8 == 0, false, false);			
			break;
		}
		case 0xb5: { // tstw
			short src = getShort(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());			
			//System.out.printf("src = %x, addr = %x\n", src, opinfo.getAddr1());
			val16 = (short)(src - 0);
			setNZVC(val16 < 0, val16 == 0, false, false);			
			break;
		}
		case 0xd5: { // tstl
			int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			//System.out.printf("src = %x\n", src);
			val64 = (long)src - 0;
			val32 = (int)val64;
			setNZVC(val32 < 0, val32 == 0, false, false);
			break;
		}
		case 0x12: { // bneq (Branch operation)
			//System.out.printf("addr = %x\n", opinfo.getAddr1());
			//System.out.println(opinfo.getType1());			
			if (!isZ()) {				
				//int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
				int src = (int)opinfo.getAddr1();
				//System.out.printf("src = %x, addr = %x\n", src, opinfo.getAddr1());
				setPc(src);
			}
			break;
		}
		case 0x13: { // beql
			if (isZ()) {				
				//int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
				int src = (int)opinfo.getAddr1();
				//System.out.printf("src = %x, addr = %x\n", src, opinfo.getAddr1());
				setPc(src);
			}
			break;
		} 
		case 0x14: { // bgtr;			
			if (!isN() && !isZ()) { // {N OR Z} EQL 0   
				int src = (int)opinfo.getAddr1();
				setPc(src);
			} 
			break;
		}
		case 0x15: { // bleq
			if (isN() || isZ()) {
				int src = (int)opinfo.getAddr1();
				setPc(src);
			}
			//System.exit(1);
			break;
		}		
		case 0x18: { // bgeq
			if (!isN()) {
				//int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
				int src = (int)opinfo.getAddr1();
				//System.out.printf("pc = %x\n", src);
				setPc(src);				
			}			
			break;
		}
		case 0x19: { // blss
			if (isN()) {
				//int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
				int src = (int)opinfo.getAddr1();
//				System.out.printf("pc = %x\n", src);
				setPc(src);
			}
			break;
		}
		case 0x1a: { // bgtru
			if (!isC() && !isZ()) {
				int src = (int)opinfo.getAddr1();
				setPc(src);
			}
			break;			
		}
		case 0x1f: { // blssu
			if (isC()) {
				int src = (int)opinfo.getAddr1();
//				System.out.printf("pc = %x\n", src);
				setPc(src);
			}			
			break;
		}
		case 0xfb: { // calls
			int arg = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			//int next = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			int next = (int)opinfo.getAddr2();
			//System.out.printf("arg = %x, next = %x\n", arg, next);
			
			System.out.printf("arg : %X, next : %X\n", arg, next);
			
			pushInt(arg); // push argument
			int tmpSp = reg[sp]; // keep current sp
			//System.out.printf("tmpSp = 0x%x\n", tmpSp);
			
			byte last2bit = (byte)(reg[sp] & 0x3);
			System.out.printf("last2bit : %X\n", last2bit);
			reg[sp] &= 0xfffffffc;
			//System.out.printf("last2bit = %x\n", last2bit);
			//System.out.printf("alined Sp = 0x%x\n", reg[sp]);
			
			
			int nextPc = memory.getCurrentPc(); // keep next pc
			//System.out.printf("nextPc = 0x%x\n", nextPc);
						
			System.out.printf("next : %X\n", next);
			
			setPc(next);                        // set pc to callee
			System.out.printf("E:nextPC : %X\n", memory.getCurrentPc());
			
			short entryMask = fetch2().sval;    // get entry mask 2bytes
			System.out.printf("entry mask = %x\n", entryMask);
			short regMask = (short)(entryMask << 4);
			System.out.printf("reg mask = %X\n", regMask);
			System.out.printf("F:nextPC : %X\n", memory.getCurrentPc());
			for (int i = 11; i >= 0; --i, regMask <<= 1) {
				if ((regMask & 0x8000) != 0) {
					System.out.printf("push reg[%d]\n", i);
					pushInt(reg[i]); // push corresponding register to the stack
				}
			}
			System.out.printf("G:nextPC : %X\n", memory.getCurrentPc());
			
			System.out.printf("mask = 0x%x\n", entryMask);
			
			// push each register value to the stack
			System.out.printf("nextPC : %X\n", nextPc);
			pushInt(nextPc);
			System.out.printf("D:nextPC : %X\n", memory.getCurrentPc());
			if (debug) {
				log.printf(" ;[%x:%x]: ", reg[sp], nextPc);
				log.printf("\n%x:", reg[pc] - 2);
				if (symTable != null && symTable.containsKey(reg[pc] - 2)) {
					log.printf(callStack.push(symTable.get(reg[pc] - 2)));
				} else {
					log.printf(callStack.push("___private___"));
				}
			}
			pushInt(reg[fp]);
			pushInt(reg[ap]);
			reg[ap] = tmpSp;			
			
			setNZVC(false, false, false, false);
			
			System.out.printf("C:nextPC : %X\n", memory.getCurrentPc());
		
			// create mask info
			int maskinfo = 0x20000000 | (last2bit << 30); // add last2bit of sp. 29,28 are fixed
			System.out.printf("maskinfo1 = %x\n", maskinfo);
			maskinfo |= (entryMask << 16) & 0x0fff0000;
			System.out.printf("maskinfo2 = %x\n", maskinfo);
			maskinfo |= psl & 0xffff;
			System.out.printf("maskinfo3 = %x\npsl : %X\n", maskinfo, psl);
			
			System.out.printf("A:nextPC : %X\n", memory.getCurrentPc());
			
			pushInt(maskinfo);
			pushInt(0);
			reg[fp] = reg[sp];
			
			// new psl
			psl |= (entryMask >> 9) & 0x20; //IV bit
			psl |= (entryMask >> 8) & 0x80; //DV bit
			
			/**  //for debug
			logOut.reset();
			storeRegInfo();
			System.out.println(new String(logOut.toByteArray()));
			logOut.reset();
			memory.dump(reg[sp], 0xffff - reg[sp] + 1);
			**/ // end of debug
			
			System.out.printf("B:nextPC : %X\n", memory.getCurrentPc());
				
			break;
		}
		case 0x4: { // ret			
			int retPc;
			reg[sp] = reg[fp] + 4; // restore stack pointer
			int maskinfo = popInt();
			//System.out.printf("sp = %x\n", reg[sp]);
			//System.out.printf("maskinfo = %x\n", maskinfo);
			
			byte last2bit = (byte)((maskinfo >> 30) & 3); // keep last 2bit
			//System.out.printf("last2bit = %x\n", last2bit);
			boolean callsFlg = ((maskinfo >> 29) & 1) == 1; // otherwise, callg
			short regMask = (short)((maskinfo >> 16) & 0xfff);
			//System.out.printf("regMask = %x\n", regMask);
			//psl |= (maskinfo & 0xffff); // restore psw
			
			psl &= 0xffff0000;
			psl |= (maskinfo & 0xffff); // restore psw
			//System.out.printf("psl %x\n", psl);
			
			// restore register
			reg[ap] = popInt();
			reg[fp] = popInt();
			setPc(retPc = popInt());
			if (debug) {
				log.printf(" ;[%x:%x]: ", reg[sp]-4, retPc);
			}

			
			//System.out.printf("nextPc = %x\n", reg[pc]);
			for (int i = 0; i <= 11; ++i, regMask >>= 1) {
				if ((regMask & 1) == 1) {
					reg[i] = popInt();            // restore r0-r11 based on the entry mask
					//System.out.printf("push reg[%d] = 0x%x\n", i, reg[i]);
				}				
			}
			//System.out.printf("sp =  %x\n", reg[sp]);
			reg[sp] += last2bit; // restore alignment
			//System.out.printf("sp =  %x\n", reg[sp]);
			if (callsFlg) { // if comming from calls
				int argnum = popInt();            // restore arg number
				//System.out.printf("sp =  %x\n", reg[sp]);
				reg[sp] += (argnum & 0xff) << 2;  // remove arguments
				//System.out.printf("sp =  %x\n", reg[sp]);
			}
			
			
			if (debug && (callStack.size() > 1)) {
				callStack.pop();
				log.printf("\n");
				for (int i = 0; i < callStack.size() - 1; ++i) {
					log.printf("-> %s", callStack.get(i));
				}
				
				log.printf("-> %s(0x%x): ", callStack.peek(), retPc);
				//log.print("\nback to : " + callStack.peek());
			}
			
			break;
			//System.exit(1);
		}
		case 0x11: { // brb
			//int nextPc = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int nextPc = (int)opinfo.getAddr1();
			setPc(nextPc);			
			break;
		}
		case 0x17: { // jmp
			int nextPc = (int)opinfo.getAddr1();
			//System.out.printf("nextpc = %x\n", nextPc);
			setPc(nextPc);			
			break;
		}
		case 0x31: { // brw
			int nextPc = (int)opinfo.getAddr1();
			setPc(nextPc);			
			break;
		}		
		case 0x1e: { // bcc
			if (!isC()) {
				//int nextPc = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
				int nextPc = (int)opinfo.getAddr1();
				setPc(nextPc);
			}
			break;
		}
		case 0x94: { // clrb			
			storeByte(opinfo.getType1(), opinfo.getAddr1(), (byte)0);
			setNZVC(false, true, false, isC());				
			break;
		}
		case 0xb4: { // clrw
			storeShort(opinfo.getType1(), opinfo.getAddr1(), (short)0);
			setNZVC(false, true, false, isC());
			break;
			
		}
		case 0xd4: { // clrl(f)
			storeInt(opinfo.getType1(), opinfo.getAddr1(), 0);
			
			setNZVC(false, true, false, isC());			
			break;			
		}
		case 0x7c: { // clrd
			storeLong(opinfo.getType1(), opinfo.getAddr1(), 0);
			setNZVC(false, true, false, isC());
			break;
		}
		case 0x96: { // incb
			long laddr;
			Type ltype;
			byte src = getByte(ltype = opinfo.getType1(), opinfo.getArg1(), laddr = opinfo.getAddr1());
			val32 = (int)src + 1;
			val8 = (byte)val32;
			storeByte(ltype, laddr, val8);
			setNZVC(val8 < 0, val8 == 0, val32 != val8, (src & 0xff) + 1 >= 0x100);
			break;
		}
		case 0xb6: { // incw
			long laddr;
			Type ltype;
			short src = getShort(ltype = opinfo.getType1(), opinfo.getArg1(), laddr = opinfo.getAddr1());
			
			//System.out.printf("src = %x\n", src);
			val32 = (int)src + 1;			
			val16 = (short)val32;
			storeShort(ltype, laddr, val16);			
			setNZVC(val16 < 0, val16 == 0, val32 != val16, (src & 0xffff) + 1 >= 0x10000);
			break;
		}
		case 0xd6: { // incl
			long laddr;
			Type ltype;
			int src = getInt(ltype = opinfo.getType1(), opinfo.getArg1(), laddr = opinfo.getAddr1());
			//System.out.printf("src = %x, addr = %x\n", src, opinfo.getAddr1());
			val64 = (long)src + 1;
			val32 = (int)val64;
			storeInt(ltype, laddr, val32);
			setNZVC(val32 < 0, val32 == 0, val64 != val32, (src & 0xffffffffL) + 1 >= 0x100000000L);			
			//setNZVC(val32 < 0, val32 == 0, val64 != val32, val64 >= (long)0x100000000L);			
			break;
		}
		case 0xb7: { // decw
			long laddr;
			Type ltype;
			short src = getShort(ltype = opinfo.getType1(), opinfo.getArg1(), laddr = opinfo.getAddr1());
			//System.out.printf("addr = %x, src = %x\n", laddr, src);
			val32 = (int)src - 1;
			val16 = (short)val32;			
			storeShort(ltype, laddr, val16);
			setNZVC(val16 < 0, val16 == 0, val32 != val16, (src & 0xffff) < (1 & 0xffff));						
			break;
		}
		case 0xd7: { // decl
			long laddr;
			Type ltype;
			int src = getInt(ltype = opinfo.getType1(), opinfo.getArg1(), laddr = opinfo.getAddr1());
//			System.out.printf("src = %x, addr = %x\n", src, laddr);
//			memory.dump(0x614, 4);
			val64 = (long)src - 1;
			val32 = (int)val64;
			storeInt(ltype, laddr, val32);
			setNZVC(val32 < 0, val32 == 0, val64 != val32, (src & 0xffffffffL) < (1 & 0xffffffffL));
			
//			memory.dump(0x614, 4);
			/*			
			System.out.printf("addr = 0x%x\n", opinfo.getAddr1());
			System.out.printf("src = 0x%x\n", src);			
			logOut.reset();
			storeRegInfo();
			System.out.println(new String(logOut.toByteArray()));
			System.exit(1);
			*/
			break;
		}
		case 0x9f: { // pushab
			int val32 = (int)opinfo.getAddr1();			
			pushInt(val32);
			setNZVC(val32 < 0, val32 == 0, false, isC());
			break;
		}
		case 0xdf: { // pushal
			int val32 = (int)opinfo.getAddr1();
			//System.out.printf("val32 in pushal = %x\n", val32);
			pushInt(val32);
			setNZVC(val32 < 0, val32 == 0, false, isC());
			/*
			logOut.reset();
			storeRegInfo();
			System.out.println(new String(logOut.toByteArray()));
			memory.dump(reg[sp], 4);			
			System.exit(1);
			*/
			break;
		}
		case 0xe0: { // bbs
			int pos = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			//int base = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			int base = getIntV(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2(), 0, 0);
			int addr = (int)opinfo.getAddr3();
			
			if (pos > 31) {
				System.err.println("Fault Happen in BBS");
				System.err.printf("pos need to be below 32 but %d\n", pos);
				System.exit(1);
			}			
			/*
			System.out.printf("0xc(r11) = ");
			memory.dump(reg[r11] + 0xc, 4);
			System.out.println();
			System.out.printf("pos = %d, base = %x, addr = %x\n", pos, base, addr);
			
			System.out.printf("pos2 = %x", opinfo.getPos2());
			memory.dump((int)opinfo.getPos2(), 4);
			*/			
			if (((base >>= pos) & 1) == 1) {
				setPc(addr);
			}
			//System.out.printf("pos = %d, base = %x, addr = %x\n", pos, base, addr);
			
			break;
		}
		case 0xe1: { // bbc
			int pos = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			//int base = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			int base = getIntV(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2(), 0, 0);
			int addr = (int)opinfo.getAddr3();
			if (pos > 31) {
				System.err.println("Fault Happen in BBC");
				System.err.printf("pos need to be below 32 but %d\n", pos);
				System.exit(1);
			}	
			
			
			if (((base >>= pos) & 1) == 0) {
				setPc(addr);
			}
			//System.out.printf("pos = %d, base = %x, addr = %x\n", pos, base, addr);
			//System.exit(1);
			break;
		}
		case 0xe3: { // bbcs
			int pos = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int base = getIntV(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2(), 0, 0);
			int addr = (int)opinfo.getAddr3();
			int newstate = (1 << pos);
			
			if (pos > 31) {
				System.err.println("Fault Happen in BBCS");
				System.err.printf("pos need to be below 32 but %d\n", pos);
				System.exit(1);
			}	
			
			val32 = (base | newstate);
			//System.out.printf("pos = %x, base = %x, addr = %x\n", pos, base, addr);
			//System.out.printf("val32 = %x\n", val32);
			storeIntV(opinfo.getType2(), opinfo.getAddr2(), val32, 0);			
			if (((base >>= pos) & 1) == 0) {
				setPc(addr);
			}
			break;
		}
		case 0xe5: { // bbcc
			int pos = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int base = getIntV(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2(), 0, 0);
			int addr = (int)opinfo.getAddr3();
			//System.out.printf("pos = %x, base = %x, addr = %x\n", pos, base, addr);
			int newstate = (1 << pos);
			
			if (pos > 31) {
				System.err.println("Fault Happen in BBCC");
				System.err.printf("pos need to be below 32 but %d\n", pos);
				System.exit(1);
			}	
			//System.out.printf("newstate = %x\n", newstate);
			
			val32 = (base & ~newstate);
			//System.out.printf("val32 = %x\n", val32);
			storeIntV(opinfo.getType2(), opinfo.getAddr2(), val32, 0);
			if (((base >> pos) & 1) == 0) {
				setPc(addr);
			}						
			break;
		}
		case 0xe8: { // blbs
			int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			long addr = opinfo.getAddr2();
			/*
			System.out.println(opinfo.getType1());
			System.out.printf("addr1 = %x\n", opinfo.getAddr1());
			System.out.printf("src = %x, addr = %x\n", src, addr);
			*/
			if ((src & 1) == 1) {
				setPc((int)addr);
			}
			break;
		}
		case 0xe9: { // blbc
			int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			long addr = opinfo.getAddr2();
			//System.out.printf("src = %x, addr = %x\n", src, addr);
			if ((src & 1) == 0) {
				setPc((int)addr);
			}			
			break;
		}
		case 0x98: { //cvtbl
			byte src = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			//System.out.printf("addr1 = %x\n", opinfo.getAddr1());
			val32 = src;	// signed convert		
			storeInt(opinfo.getType2(), opinfo.getAddr2(), val32);
			
			setNZVC(val32 < 0, val32 == 0, false, false); // V is always false beccause
			//memory.dump((int)opinfo.getAddr2(), 4);     // small will be converted to large
			
			break;
		}
		case 0x99: { // cvtbw
			byte src = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			val16 = src;	// signed convert
			//System.out.printf("src = %x\n", src);
			storeShort(opinfo.getType2(), opinfo.getAddr2(), val16);
			setNZVC(val16 < 0, val16 == 0, false, false);
			//System.exit(1);
			break;
		}
		case 0x32: { // cvtwl
			short src = getShort(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			//System.out.printf("arg1 = %x, src = %x\n", opinfo.getArg1(), src);
			val32 = src; // signed convert
			storeInt(opinfo.getType2(), opinfo.getAddr2(), val32);
			//System.out.printf("addr2 = %x\n", opinfo.getAddr2());
			//memory.dump((int)opinfo.getAddr2(), 4);
			setNZVC(val32 < 0, val32 == 0, src != val32, false); // overflow never happens			
			break;
		}
		case 0x33: { // cvtwb
			short src = getShort(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());			
			byte dst = (byte)src;
			//System.out.printf("src = %x, dst = %x\n", src, dst);
			storeByte(opinfo.getType2(), opinfo.getAddr2(), dst);
			setNZVC(dst < 0, dst == 0, src != dst, false);						
			break;
		}
		case 0xf6: { // cvtlb
			int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			byte dst = (byte)src;
			//System.out.println("type = " + opinfo.getType1());
			//System.out.printf("dst = %x\n", dst);
			//System.out.printf("addr1 = %x, src = %x\n", opinfo.getAddr1(), src);
			//memory.dump((int)opinfo.getAddr1(), 4);
			//System.out.printf("addr2 = %x\n", opinfo.getAddr2());			
			//memory.dump((int)opinfo.getAddr2(), 4);
			
			storeByte(opinfo.getType2(), opinfo.getAddr2(), dst);
			
			//memory.dump((int)opinfo.getAddr2(), 4);
			setNZVC(dst < 0, dst == 0, src != dst, false);
			
			//System.exit(1);
			break;
		}
		case 0xf7: { // cvtlw
			int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			val16 = (short)src;
			storeShort(opinfo.getType2(), opinfo.getAddr2(), val16);
			//memory.dump((int)opinfo.getAddr2(), 4);
			setNZVC(val16 < 0, val16 == 0, src != val16, false);						
			break;
		}
		case 0x88: { //bisb2
			long laddr = 0;
			Type ltype;
			byte mask = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			byte dst = getByte(ltype = opinfo.getType2(), opinfo.getArg2(), laddr = opinfo.getAddr2());
			//System.out.printf("addr2 = %x\n", laddr);
			//System.out.printf("mask = %x, dst = %x\n", mask, dst);
			//memory.dump(laddr, 4);
			dst |= mask;
			//System.out.printf("mask = %x, dst = %x\n", mask, dst);
			storeByte(ltype, laddr, dst);
			//memory.dump(0x620, 4);
			//setNZVC(val32 < 0, val32 == 0, false, isC());			
			setNZVC(dst < 0, dst == 0, false, isC());
			break;			
		}
		case 0xa8: { // bisw2
			long laddr = 0;
			Type ltype;
			short mask = getShort(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			short dst  = getShort(ltype = opinfo.getType2(), opinfo.getArg2(), laddr = opinfo.getAddr2());
			dst |= mask;
			//System.out.printf("mask = %x, dst = %x, laddr = %x\n", mask, dst, laddr);
			//memory.dump((int)laddr, 4);
			storeShort(ltype, laddr, dst);
			//memory.dump((int)laddr, 4);
			setNZVC(dst < 0, dst == 0, false, isC());			
			break;
		}
		case 0xc8: { // bisl2
			long laddr = 0;
			Type ltype;
			int mask = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int dst  = getInt(ltype = opinfo.getType2(), opinfo.getArg2(), laddr = opinfo.getAddr2());
			//System.out.printf("mask = %x, dst = %x\n", mask, dst);
			dst |= mask;
			storeInt(ltype, laddr, dst);
			setNZVC(dst < 0, dst == 0, false, isC());			
			break;			
		}
		case 0xc9: { // bisl3
			int mask = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int src  = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			/*
			System.out.printf("mask = %x, src = %x, \n", mask, src);
			System.out.println("type =" + opinfo.getType3());
			System.out.printf("dst = %x\n", opinfo.getAddr3());
			*/
			
			val32 = src | mask;
			//System.out.printf("val32 = %x\n", val32);
			//memory.dump((int)opinfo.getAddr3(), 4);
			storeInt(opinfo.getType3(), opinfo.getAddr3(), val32);
			//memory.dump((int)opinfo.getAddr3(), 4);
			setNZVC(val32 < 0, val32 == 0, false, isC());						
			break;
		}
		case 0x8e: { // mnegb
			byte src = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			//System.out.printf("src = %x\n", src);
			val32 = src * -1;
			val8 = (byte)val32;			
			storeByte(opinfo.getType2(), opinfo.getAddr2(), val8);						
			setNZVC(val8 < 0, val16 == 0, val32 != val8, val8 != 0);
			break;
		}
		case 0xae: { // mnegw
			short src = getShort(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			val32 = src * -1;
			val16 = (short)val32;
			//System.out.printf("src = %x, val32 = %x, val16 = %x\n", src, val32, val16);
			storeShort(opinfo.getType2(), opinfo.getAddr2(), val16);
			setNZVC(val16 < 0, val16 == 0, val32 != val16, val16 != 0);
			
			break;
		}
		case 0xce: { // mnegl
			int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			//System.out.printf("src = %x\n", src);
			//src = 0x80000000;
			//src = 0x7fffffff;
			//System.out.printf("src = %d\n", src);
			val64 = (long)src * -1;
			val32 = (int)val64;
			//System.out.printf("val64 = %d, val32 = %d\n", val64, val32);
			storeInt(opinfo.getType2(), opinfo.getAddr2(), val32);
			setNZVC(val32 < 0, val32 == 0, val64 != val32, val32 != 0);			
			break;
		}
		case 0x93: { // bitb
			byte mask = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			byte src  = getByte(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			//System.out.printf("mask = %x, src = %x\n", mask, src);
			//System.out.printf("addr2 = %x\n", opinfo.getAddr2());
			//memory.dump((int)opinfo.getAddr2(), 4);
			val8 = (byte)(mask & src);
			//System.out.printf("val8 = %x\n", val8);
			//setNZVC(val32 < 0, val32 == 0, false, isC());			
			setNZVC(val8 < 0, val8 == 0, false, isC());
			break;
		}
		case 0xb3: { // bitw
			short mask = getShort(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			short src  = getShort(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			//System.out.printf("mask = %x, src= %x, src_addr = %x\n", mask, src, opinfo.getAddr2());
			val16 = (short)(mask & src);
			//System.out.printf("val16 = %x\n", val16);
			setNZVC(val16 < 0, val16 == 0, false, isC());			
			break;
		}
		case 0xd3: { // bitl
			int mask = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int src = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());			
			//System.out.printf("mask = %x, src = %x\n", mask, src);
			val32 = mask & src;
			setNZVC(val32 < 0, val32 == 0, false, isC());
			break;
		}
		case 0xca: { // bicl2
			long laddr;
			Type ltype;
			int mask = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int dst  = getInt(ltype = opinfo.getType2(), opinfo.getArg2(), laddr = opinfo.getAddr2());						
			//System.out.printf("mask = %x, dst = %x\n", mask, dst);
			val32 = dst & ~mask;
			storeInt(ltype, laddr, val32);
			setNZVC(val32 < 0, val32 == 0, false, isC());									
			break;
		}
		case 0xcb: { // bicl3
			int mask = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int src  = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			//System.out.printf("mask = %x, src = %x\n", mask, src);
			val32 = src & ~mask;
			storeInt(opinfo.getType3(), opinfo.getAddr3(), val32);
			//System.out.printf("val32 = %x\n", val32);
			setNZVC(val32 < 0, val32 == 0, false, isC());
			//System.exit(1);
			break;
		}
		case 0x8a: { // bicb2
			long laddr;
			Type ltype;
			byte mask = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			byte dst  = getByte(ltype = opinfo.getType2(), opinfo.getArg2(), laddr = opinfo.getAddr2());
			//System.out.printf("mask = %x, dst = %x\n", mask, dst);
			//System.out.printf("addr2 = %x\n", opinfo.getAddr2());
			//memory.dump(0xc18, 4);
			val32 = val8 = (byte)(dst & ~mask);
			setNZVC(val32 < 0, val32 == 0, false, isC());
			storeByte(ltype, laddr, val8);
			//memory.dump(0xc18, 4);			
			break;
		}
		case 0x80: { // addb2
			long laddr;
			Type ltype;
			byte src = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			byte dst = getByte(ltype = opinfo.getType2(), opinfo.getArg2(), laddr = opinfo.getAddr2());
			
			//memory.dump((int)laddr, 4);
			val32 = src + dst;
			val8 = (byte)val32;
			storeByte(ltype, laddr, val8);
			setNZVC(val8 < 0, val8 == 0, val8 != val32, (src & 0xff) + (dst & 0xff) >= 0x100);
			//memory.dump((int)laddr, 4);
			break;
		}
		case 0xc0: { // addl2
			long laddr;
			int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int dst = getInt(opinfo.getType2(), opinfo.getArg2(), laddr = opinfo.getAddr2());
			//System.out.printf("src = %x, dst = %x\n", src, dst);
			
			val64 = (long)src + (long)dst;
			val32 = (int)val64;
			//System.out.printf("val32 = %x\n", val32);
			storeInt(opinfo.getType2(), laddr, val32);
			//memory.dump((int)laddr, 4);
			
			
			setNZVC(val32 < 0, val32 == 0, val64 != val32, (src & 0xffffffffL) + (dst & 0xffffffffL) >= 0x100000000L);			
			break;
		}
		case 0xc1: { // addl3
			int src1 = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int src2 = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			//System.out.printf("src1 = %x, src2 = %x, addr = %x\n", src1, src2, opinfo.getAddr3());
			val64 = (long)src1 + (long)src2;
			val32 = (int)val64;
			storeInt(opinfo.getType3(), opinfo.getAddr3(), val32);
			setNZVC(val32 < 0, val32 == 0, val64 != val32, (src1 & 0xffffffffL) + (src2 & 0xffffffffL) >= 0x100000000L);
//			memory.dump(0xfff84, 4);

			break;			
		}
		case 0xf4: { // sobgeq
			//memory.dump(0xc1c, 4);
			int index = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			//int addr = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			int addr = (int)opinfo.getAddr2();
			//System.out.printf("index = %x, addr = %x\n", index, addr);
			//System.out.printf("addr = %x getAddr2 = %x\n", addr, opinfo.getAddr2());
			val64 = (long)index - (long)1;
			val32 = (int)val64;
			//System.out.printf("val32 = %x\n", val32);
			storeInt(opinfo.getType1(), opinfo.getAddr1(), val32);			
			setNZVC(val32 < 0, val32 == 0, val64 != val32, isC());			
			if (val32 >= 0) {
				setPc(addr);
			}									
			break;
		}
		case 0xf5: {
			int index = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int addr = (int)opinfo.getAddr2();
			//System.out.printf("index = %x, addr = %x\n", index, addr);
			val64 = (long)index - (long)1;
			val32 = (int)val64;
			storeInt(opinfo.getType1(), opinfo.getAddr1(), val32);
			setNZVC(val32 < 0, val32 == 0, val64 != val32, isC());
			if (val32 > 0) {
				setPc(addr);
			}
			break;
		}
		case 0x9a: { // movzbl
			byte src = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			val32 = (int)(src & 0xff); // unsigned extend
			//System.out.printf("src = %x\n", src);
			//System.out.printf("val32 = %x\n", val32);
			storeInt(opinfo.getType2(), opinfo.getAddr2(), val32);
			setNZVC(false, val32 == 0, false, isC());						
			break;
		}
		case 0x3c: { // movzwl
			short src = getShort(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			//System.out.printf("(fp) = %x, src = %x, addr=%x\n", reg[fp], src, opinfo.getAddr1());
			//memory.dump((int)opinfo.getAddr1(), 4);
			val32 = (int)(src & 0xffff);
			storeInt(opinfo.getType2(), opinfo.getAddr2(), val32);
			setNZVC(false, val32 == 0, false, isC());
			break;
		}		
		case 0xee: { // extv
			int pos = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			byte size= getByte(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());			
			// bitfield access
			int base = getIntV(opinfo.getType3(), opinfo.getArg3(), opinfo.getAddr3(), pos, opinfo.getPos3());
			int mask = 0;
			for (int i = 0; i < size; ++i) {
				mask = (mask << 1) | 1;
			}
			System.out.printf("mask = %x\n", mask);
			base &= mask;
			
			int signbit = (base >> (size - 1)) & 1;
			int mask2 = signbit;
			for (int i = 0; i < (32 - size); ++i) {
				mask2 = (mask2 << 1) | signbit;
			}
			mask2 <<= size;
			System.out.printf("mask2 = %x\n", mask2);
			base |= mask2;
			storeInt(opinfo.getType4(), opinfo.getAddr4(), base);
			setNZVC(base < 0, base == 0, false, false);			
			System.out.printf("pos = %x, size = %x, base = %x\n", pos, size, base);
			// for temporary use
			System.out.println("extv is not confirmed just implemented as extzv");
			
			logOut.reset();
			storeRegInfo();
			System.out.println(new String(logOut.toByteArray()));
			logOut.reset();
			
			System.exit(1);
			break;
		}
		case 0xef: { // extzv
			int pos = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			byte size= getByte(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());			
			// bitfield access
			int base = getIntV(opinfo.getType3(), opinfo.getArg3(), opinfo.getAddr3(), pos, opinfo.getPos3());
			int mask = 0;
			for (int i = 0; i < size; ++i) {
				mask = (mask << 1) | 1;
			}
			
			if (size > 32) {
				System.err.println("Fault Happen in EXTZV");
				System.err.printf("size need to be below 32 but %d\n", size);
				System.exit(1);
			}
			if (pos > 31) {
				System.err.println("Fault Happen in EXTZV");
				System.err.printf("pos need to be below 32 but %d\n", pos);
				System.exit(1);
			}	
			//System.out.printf("mask = %x\n", mask);			
			base &= mask;
			storeInt(opinfo.getType4(), opinfo.getAddr4(), base);
			setNZVC(base < 0, base == 0, false, false);					
			//System.out.printf("pos = %x, size = %x, base = %x\n", pos, size, base);
			//System.out.printf("reg1 = %x\n", reg[r1]);			
			break;
		}
		case 0xcf: { // casel
			int sel = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int base = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			int limit = getInt(opinfo.getType3(), opinfo.getArg3(), opinfo.getAddr3());
			int tmp = sel - base;
//			System.out.printf("sel = %x, base = %x, limit = %x, tmp = %x\n", sel, base, limit, tmp);
			int offset = 0;
			boolean cflg;
			if ((cflg = (tmp & 0xffffffffL) < (limit & 0xffffffffL)) || (tmp == limit)) {
				offset = memory.readShort(reg[pc] + (tmp * 2));
//				System.out.printf("offset(true) = %x\n", offset);
			} else {
				offset = (limit + 1) * 2;
				//System.out.printf("offset(false) = %x\n", offset);
			}
			int nextpc = offset + reg[pc];
			//System.out.printf("nextpc = %x\n", nextpc);
			//memory.dump(0xfffa0, 4);
			setPc(nextpc);
			setNZVC(tmp < limit, tmp == limit, false, cflg);					
			break;
		}
		case 0xf9: { // cvtlp
			int laddr;
			int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int dstlen = getShort(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());			
			//System.out.printf("src = %x, dstlen = %x, addr3 = %x\n", src, dstlen, opinfo.getAddr3());
			byte[] decdata = BCDUtil.int2bcd(src);
			byte[] newdata = BCDUtil.convert(decdata, dstlen);
			/*
			for (int i = 0; i < decdata.length; ++i) {
				System.out.printf("%02x ", decdata[i]);
			}
			System.out.println();
			for (int i = 0; i < newdata.length; ++i) {
				System.out.printf("%02x ", newdata[i]);
			}
			System.out.println();
			*/
			
			int val32 = BCDUtil.bcd2int(newdata);
			//System.out.println("val32 = " + val32);			
			//memory.dump((int)opinfo.getAddr3(), 6);
			storeRawData(newdata, laddr = (int)opinfo.getAddr3());
			//memory.dump((int)opinfo.getAddr3(), 6);
			setNZVC(val32 < 0, val32 == 0, (src != val32), false);
			reg[r0] = reg[r1] = reg[r2] = 0;
			reg[r3] = laddr;
			
			//System.exit(1);
			break;
		}
		case 0x38: { // editpc
			short srclen = getShort(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			long srcaddr = opinfo.getAddr2();
			long pataddr = opinfo.getAddr3();
			long dstaddr = opinfo.getAddr4();
			/*
			System.out.printf("srclen = %x, srcaddr= %x, pataddr= %x, dstaddr = %x\n", srclen,
					srcaddr, pataddr, dstaddr);
					*/
			val32 = execEditPc(srclen, srcaddr, pataddr, dstaddr);
			
			reg[r0] = srclen & 0xffff;
			reg[r1] = (int)srcaddr;
			reg[r2] = reg[r4] = 0;
			/*
			memory.dump(0x1000, 20);
			System.out.printf("r0=%x, r1=%x, r2=%x, r3=%x, r4=%x, r5=%x\n",
					reg[r0], reg[r1], reg[r2], reg[r3], reg[r4], reg[r5]);
			*/
			setNZVC(val32 < 0, isZ(), false, isC()); // Z and C is changed already
			break;
		}
		case 0x3a: { // locc
			byte c = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			short len = getShort(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			long addr = opinfo.getAddr3();
			//System.out.printf("c = %x, len = %x, addr = %x\n", c, len, addr);
			reg[r0] = len & 0xffff; // and operation is required
			//System.out.printf("reg= %d\n", reg[r0]);
			boolean found = false;
			for (; reg[r0] > 0; --reg[r0]) {
				byte tc = memory.readByte((int)addr++);
				if (tc == c) {
					//System.out.println("find!");
					reg[r1] = (int)(--addr);
					found = true;
					break;
				} 				
			}
			if (!found) {
				reg[r1] = (int)addr;
			}
			setNZVC(false, reg[r0] == 0, false, false);					
			//System.out.printf("reg0 = %x, reg1 = %x\n", reg[r0], reg[r1]);
			break;
		}
		case 0x3b: { // skpc
			byte c = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			short len = getShort(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			long addr = opinfo.getAddr3();
//			System.out.printf("c = %x, len = %x, addr = %x\n", c, len, addr);
			reg[r0] = len;
			boolean found = false;
			for (; reg[r0] > 0; --reg[r0]) {
				byte tc = memory.readByte((int)addr++);
				if (tc != c) {
					reg[r1] = (int)(--addr);
					found = true;
					break;
				} 
			}
			if (!found) {
				reg[r1] = (int)addr;
			}
			//System.out.printf("reg0 = %x, reg1 = %x\n", reg[r0], reg[r1]);
			setNZVC(false, reg[r0] == 0, false, false);			
			break;
		}
		case 0x78: { // ashl
			byte cnt = getByte(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int src = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
//			System.out.printf("cnt = %x, src = %x\n", cnt, src);
			val64 = (cnt < 0) ? ((long)src >> (~cnt + 1)) : ((long)src << cnt);
			val32 = (int)val64;
			//System.out.printf("val32 = %x\n", val32);
			//System.out.printf("val64 = %x\n", val64);
			storeInt(opinfo.getType3(), opinfo.getAddr3(), val32);
			setNZVC(val32 < 0, val32 == 0, val64 != val32, false);
			break;
		}
		case 0xc6: { // divl2
			Type ltype;
			long laddr;
			int divr = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int quo  = getInt(ltype = opinfo.getType2(), opinfo.getArg2(), laddr = opinfo.getAddr2());
			
			//System.out.printf("divr = %x, quo = %x\n", divr, quo);			
			if (divr == 0) {
				setNZVC(false, false, true, false);
				System.err.println("div 0 error in run");
				System.exit(1);
			}
			val64 = (long)quo / (long)divr;
			val32 = (int)val64;
			
			//System.out.printf("val32 = %x\n", val32);
			storeInt(ltype, laddr, val32);
			setNZVC(val32 < 0, val32 == 0, val64 != val32, false);			
			break;
		}
		case 0xc7: { // divl3
			int divr = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int divd = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
//			System.out.printf("divr = %x, divd = %x\n", divr, divd);
			
			if (divr == 0) {
				setNZVC(false, false, true, false);
				System.err.println("div 0 error in run");
				System.exit(1);
			}
			
			val64 = (long)divd / (long)divr;
			val32 = (int)val64;
//			System.out.printf("val32 = %x\n", val32);
			storeInt(opinfo.getType3(), opinfo.getAddr3(), val32);
			setNZVC(val32 < 0, val32 == 0, val64 != val32, false);
			//System.exit(1);
			break;
		}
		case 0xf1: { // acbl
			Type ltype;
			long laddr;
			int limit = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int add   = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			int index = getInt(ltype = opinfo.getType3(), opinfo.getArg3(), laddr = opinfo.getAddr3());
			int nextPc = (int)opinfo.getAddr4();
			
			//System.out.printf("limit = %x, add = %x, index = %x\n", limit, add, index);
			//System.out.printf("nextPc = %x\n", nextPc);
			val64 = (long)add + (long)index;
			val32 = (int)val64;			
			if ((add >= 0) && (val32 <= limit) ||
					((add < 0) && (val32 >= limit))) {
				setPc(nextPc);
			} 
			//System.out.printf("limit = %x, add = %x, index = %x\n", limit, add, val32);
			storeInt(ltype, laddr, val32);
			setNZVC(val32 < 0, val32 == 0, val64 != val32, isC());						
			break;
		}
		case 0x28: { // movc3
			int len = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			//System.out.printf("len = %x, src = %x, dst = %x\n", len, opinfo.getAddr2(), opinfo.getAddr3());
			byte[] tmp = memory.rawRead((int)opinfo.getAddr2(), len);
			memory.rawWrite(tmp, 0, (int)opinfo.getAddr3(), len);
			reg[r0] = reg[r2] = reg[r4] = reg[r5] = 0;
			reg[r1] = (int)opinfo.getAddr2() + len;
			reg[r3] = (int)opinfo.getAddr3() + len;
			setNZVC(false, true, false, false);
			
			//memory.dump((int)opinfo.getAddr2(), len);
			//System.out.println("stepcount = " + stepCount);
			//System.exit(1);
			break;
		}
		case 0xc4: { // mull2
			Type ltype;
			long laddr;
			int mulr = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int prod = getInt(ltype = opinfo.getType2(), opinfo.getArg2(), laddr = opinfo.getAddr2());
			//System.out.printf("mulr = %x, prod = %x\n", mulr, prod);
			val64 = (long)(mulr & 0xffffffffL) * (long)(prod & 0xffffffffL);
			val32 = (int)val64;
			storeInt(ltype, laddr, val32);
			setNZVC(val32 < 0, val32 == 0, val64 != val32, false);			
			break;
		}
		case 0xc5: { // mull3
			int mulr = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int muld = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			//System.out.printf("mulr = %x, muld = %x\n", mulr, muld);			
			val64 = (long)(mulr & 0xffffffffL) * (long)(muld & 0xffffffffL);
			val32 = (int)val64;
			storeInt(opinfo.getType3(), opinfo.getAddr3(), val32);
			//System.out.printf("val64 = %x, val32 = %x\n", val64, val32);
			setNZVC(val32 < 0, val32 == 0, val64 != val32, false);			
			break;
		}
		case 0xd2: { // mcoml
			int src = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			val32 = ~src;			
			storeInt(opinfo.getType2(), opinfo.getAddr2(), val32);
			setNZVC(val32 < 0, val32 == 0, false, isC());						
			break;
		}
		case 0xf2: { // aoblss
			Type ltype;
			long laddr;
			long limit = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1()) & 0xffffffffL;
			long index = getInt(ltype = opinfo.getType2(), opinfo.getArg2(), laddr = opinfo.getAddr2()) & 0xffffffffL;
			storeInt(ltype, laddr, (int)++index);
			if (index < limit) {
				setPc((int)opinfo.getAddr3());				
			}
			val32 = (int)index;
			setNZVC(val32 < 0, val32 == 0, index != val32, isC());			
			break;
		}
		case 0xf3: { // aobleq
			Type ltype;
			long laddr;
			long limit = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1()) & 0xffffffffL;
			long index = getInt(ltype = opinfo.getType2(), opinfo.getArg2(), laddr = opinfo.getAddr2()) & 0xffffffffL;
			storeInt(ltype, laddr, (int)++index);
			if (index <= limit) {
				setPc((int)opinfo.getAddr3());				
			}
			val32 = (int)index;
			setNZVC(val32 < 0, val32 == 0, index != val32, isC());	
			break;
		}
		case 0xcc: { // xorl2
			Type ltype;
			long laddr;
			int mask = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int dst = getInt(ltype = opinfo.getType2(), opinfo.getArg2(), laddr = opinfo.getAddr2());
			val32 = dst ^ mask;
			storeInt(ltype, laddr, val32);
			setNZVC(val32 < 0, val32 == 0, false, isC());
			
			//System.exit(1);
			break;
		}
		case 0xcd: { // xorl3
			int mask = getInt(opinfo.getType1(), opinfo.getArg1(), opinfo.getAddr1());
			int src = getInt(opinfo.getType2(), opinfo.getArg2(), opinfo.getAddr2());
			val32 = src ^ mask;
			//System.out.printf("mask = %x, src = %x, val32 = %x\n", mask, src, val32);
			//System.out.printf("src addr = %x\n", opinfo.getAddr2());
			//memory.dump((int)opinfo.getAddr2(), 4);
			storeInt(opinfo.getType3(), opinfo.getAddr3(), val32);
			setNZVC(val32 < 0, val32 == 0, false, isC());			
			//System.exit(1);
			break;
		}
		default: {
			System.out.printf("unrecognised operator: 0x%x in run\n", ope.mne);
			System.out.printf("stepCount = %d\n", stepCount);
			System.exit(1);
		}
		}		
		if (debug && logOut.size() > 0) {
			System.out.println(new String(logOut.toByteArray()));
			//memory.dump(0xfff60, 4); // for debug
			logOut.reset();
		}
		
		return 0;
		
	}
	
	@Override
	public Cpu clone() {
		Cpu cpu = null;
		try {
			cpu = (Cpu)super.clone();
			cpu.reg = new int[16];
			for (int i = 0; i < cpu.reg.length; ++i) {
				cpu.reg[i] = this.reg[i];
			}
			cpu.memory = null;
			cpu.opinfo = new OpInfo();
			cpu.os = null;
			cpu.ctx = null;
			cpu.logOut = new ByteArrayOutputStream();
			cpu.logOut.write(this.logOut.toByteArray());
			cpu.log = new PrintStream(cpu.logOut);
			cpu.symTable = new HashMap<Integer, String>();
			for (Map.Entry<Integer, String> e : this.symTable.entrySet()) {
				cpu.symTable.put(e.getKey(), e.getValue());
			}
			cpu.callStack = new Stack<String>();
			for (int i = 0; i < this.callStack.size(); ++i) {
				cpu.callStack.push(this.callStack.get(i));
			}
			
			
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException();
		}		
		
		return cpu;
	}
	
	private void executeFloat(NibbleReader nr, int count) {
		//System.out.printf("count = %d, addr = %x\n", count, reg[r5]);		
		for (int i = 0; i < count; ++i) {
			byte val = nr.readNext();	
			
			if (val != 0) {
				if (!isC()) {
					setCarry();	clearZero();
					byte signflg = nr.getLast();
					if (signflg == 0xc) { // plus
						//System.out.printf("sign:0x20 ");
						memory.writeByte(reg[r5]++, (byte)0x20);
					} else if (signflg == 0xd) { // minus
						//System.out.printf("sign:0x2d ");
						memory.writeByte(reg[r5]++, (byte)0x2d);
					} else { // default
						//System.out.printf("sign:0x20 ");
						memory.writeByte(reg[r5]++, (byte)0x20);
					}
				} 
				//System.out.printf("%02x:0x30 ", val + 0x30);
				memory.writeByte(reg[r5]++, (byte)(val + 0x30));
			} else {
				if (isC()) {
					//System.out.printf("%02x:0x30 ", val);
					memory.writeByte(reg[r5]++, (byte)0x30); // 0(zero)
				} else {
					//System.out.printf("%02x:0x20 ", val);
					memory.writeByte(reg[r5]++, (byte)0x20); // sp(space)
				}
			}					
		}
		//System.out.println();
	}
	
	private void executeMove(NibbleReader nr, int count) {
		//System.out.printf("count = %d, addr = %x\n", count, reg[r5]);		
		for (int i = 0; i < count; ++i) {
			byte val = nr.readNext();				
			if (val != 0) {
				if (!isC()) {
					setCarry();	clearZero();
				} 
				//System.out.printf("%02x:0x30 ", val + 0x30);
				memory.writeByte(reg[r5]++, (byte)(val + 0x30));
			} else {
				//System.out.printf("%02x:0x30 ", val);
				memory.writeByte(reg[r5]++, (byte)0x30);
			}					
		}
		//System.out.println();
	}
	
	private void executeEndFloat(NibbleReader nr) {
		if (!isC()) {
			setCarry();
			byte signflg = nr.getLast();
			if (signflg == 0xc) {
				//System.out.printf("sign:0x20\n");
				memory.writeByte(reg[r5]++, (byte)0x20);
			} else if (signflg == 0xd) {
				//System.out.printf("sign:0x2d\n");
				memory.writeByte(reg[r5]++, (byte)0x2d);
			}		
		} else { //just debug
			//System.out.println("do nothing");
		}
	}
	
	
	
	/**
	 * Execute EditPc
	 * @param srclen
	 * @param srcaddr
	 * @param pataddr
	 * @param dstaddr
	 * @return
	 */
	private int execEditPc(short srclen, long srcaddr, long pataddr, long dstaddr) {
		int srcdatalen = (srclen / 2) + 1;
		byte[] rawData = memory.rawRead((int)srcaddr, srcdatalen);
		
		/*
		for (int i = 0; i < rawData.length; ++i) {
			System.out.printf("%02x ", rawData[i]);
		}
		System.out.println();
		*/
		
		int rval = BCDUtil.bcd2int(rawData);
		
		NibbleReader nr = new NibbleReader(srclen, rawData);
		/*
		for (int i = 0; i < srclen; ++i) {
			System.out.printf("%02x ", nr.readNext());
		}
		byte last = nr.getLast();
		System.out.printf("\nlast = %02x\n", last);
		*/
		reg[r5] = (int)dstaddr;
		
		
		nr.resetPos(srclen);
		while(true) {
			int pattern = (memory.readByte((int)pataddr++)) & 0xff;
			if (pattern == 0) {
				reg[r3] = (int)(--pataddr);
				break;
			}
			switch (pattern) {
			case 0x01: { // EO$end_float
				executeEndFloat(nr);
				break;
			}
			case 0x91:
			case 0x92:
			case 0x93:
			case 0x94:
			case 0x95:
			case 0x96:
			case 0x97:
			case 0x98:
			case 0x99:
			case 0x9a:
			case 0x9b:
			case 0x9c:
			case 0x9d:
			case 0x9e:
			case 0x9f: { // EO$MOVE
				//executeFloat(nr, pattern & 0xf);
				executeMove(nr, pattern & 0xf);
				break;
			}
			case 0xa1: 
			case 0xa2: 
			case 0xa3: 
			case 0xa4: 
			case 0xa5: 
			case 0xa6: 
			case 0xa7: 
			case 0xa8: 
			case 0xa9: 
			case 0xaa: 
			case 0xab: 
			case 0xac: 
			case 0xad: 
			case 0xae: 
			case 0xaf:{ // EO$Float
				executeFloat(nr, pattern & 0xf);
				break;
			}			
			default: {
				System.out.printf("unknown edit pattern type in editpc %x\n", pattern);
				System.exit(1);
				break;
			}				
			}					
			//System.out.printf("%02x ", pattern);	
		}
		
		return rval;
	}
	
	private class NibbleReader {
		private int pos;
		private byte[] data;		
		public NibbleReader(int len, byte[] data) {
			this.data = data;
			resetPos(len);
		}		
		public void resetPos(int len) {
			pos = ((len % 2) == 0) ? 1 : 0;			
		}		
		public byte readNext() {
			int index = pos / 2;			
			return ((pos++ % 2)) == 0 ? (byte)((data[index] >> 4) & 0xf) :  
				(byte)(data[index] & 0xf);				
		}		
		public byte getLast() {
			return (byte)(data[data.length-1] & 0xf);
		}
	}

}
