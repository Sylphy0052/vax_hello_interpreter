package hello;

import java.util.HashMap;

import hello.Operand.Relative;
import hello.Operand.Type;

public class BinaryAnalyze {

	enum Mode {
		//汎用レジスタ・アドレッシング・モード
		NONE, //リテラル
		R4, //インデックス
		R5, //レジスタ
		R6, //レジスタ・ディファード
		R7, //オートデクリメント
		R8, //オートインクリメント
		R9, //オートインクリメント・ディファード
		RA, //バイト・ディスプレイスメント
		RB, //バイト・ディスプレイスメント・ディファード
		RC, //ワード・ディスプレイスメント
		RD, //ワード・ディスプレイスメント・ディファード
		RE, //ロングワード・ディスプレイスメント
		RF, //ロングワード・ディスプレイスメント・ディファード
		//プログラム・カウンタ・アドレッシング・モード
		P8, //イミディエイト
		P9, //アブソリュート
		PA, //バイト・リラティブ
		PB, //バイト・リラティブ・ディファード
		PC, //ワード・リラティブ
		PD, //ワード・リラティブ・ディファード
		PE, //ロングワード・リラティブ
		PF //ロングワード・リラティブ・ディファード
	}

	private byte[] buf;
	private byte buf1, buf2, buf3, buf4, buf5;
	
	private Display display;

	private HashMap<Byte, Operation> map;

	private int num;
	private int pos;
	private int textSize;

	private final String[] headerExplain = {
			"マジックナンバー", 
			"テキストセグメントのサイズ", 
			"初期化データのサイズ", 
			"未初期化データのサイズ", 
			"シンボルテーブルのサイズ", 
			"プログラムの実行開始アドレス", 
			"テキスト再配置情報のサイズ", 
			"データ再配置情報のサイズ"};

	public BinaryAnalyze(byte[] buf) {
		this.buf = buf;
		map = new HashMap<Byte, Operation>();
		pos = 0;
		display = new Display(buf);
		registOperation();
	}

	public void analyze() {
		header();
		do {
			operationRead();
			display.showOperation();
		} while(pos < textSize + 33);
	}

	private void header() {
		byte[] b = new byte[64];

		do {
			b[pos] = buf[pos++];
		} while(pos < 32);

		System.out.println("*** header ***");
		int num = 0;

		byte buf1 = b[4];
		byte buf2 = b[5];
		byte buf3 = b[6];
		byte buf4 = b[7];

		textSize = (buf1 & 0xFF) | ((buf2 & 0xFF) << 8) | ((buf3 & 0xFF) << 16) | ((buf4 & 0xFF) << 24);

		for(int i = 0; i < 32; i++) {
			System.out.print(String.format("%02X ", b[i]));

			if(i % 4 == 3) {
				System.out.println(" : " + headerExplain[num++]);
			}
		}
		
		System.out.println("*** header ***\n\n");

	}

	private void operationRead() {
		Operation operation = (Operation)map.get((byte)buf[pos++]);
		if(operation == null) {
			System.out.println("Operation UNDEFINED : " + String.format("%02X",buf[pos - 1]));
		}
		
		display.showFirstBinary();
		display.setOperandNum(operation.getOperandNum());
		display.setOperationName(operation.getName());
		
		if(operation.getOperandNum() == 0) {
			return;
		}
		
		for(Operand operand : operation.getOperand()) {
			readOperand(operand);
		}
	}

	private void readOperand(Operand operand) {
		byte b = buf[pos++];
		byte b1 = (byte)((b >> 4) & (byte)0xf);
		byte b2 = (byte)(b & (byte)0xf);
		
		display.showBinary();
		
		if(operand.getType() == Type.UNDEFINED) {
			buf1 = buf[pos-2];
			num = (b & 0xFF) + ((buf1 & 0xFF) << 8);
			display.append(String.format("0x%04X", num));
			return;
		}
		
		if(operand.getRel() == Relative.REL) { //相対アドレス
			if(operand.getType() == Type.WORD) {
				buf1 = buf[pos++];
				num = (short)((b & 0xFF) + ((buf1 & 0xFF) << 8)); 
				display.showBinary();
				num += pos - 32; 
				display.append(String.format("0x%X", num));
			} else if(operand.getType() == Type.BYTE) {
				num = (byte)(b & 0xFF);
				num += pos - 32; 
				display.append(String.format("0x%X", num));
			} else {
				num = b + pos - 32;
				display.append(String.format("0x%X", num));
			}
		} else {
			String str = adressingMode(iAdressingMode(b1, b2), b, operand);
			display.append(str);
		
		}
	}
	
	private String readRegister(byte b2) {
		switch(b2) {
		case (byte)0xC: return "AP";
		case (byte)0xD: return "FP";
		case (byte)0xE: return "SP";
		case (byte)0xF: return "PC";
		default: return "R" + String.format("%d", b2);
		}
	}

	private Mode iAdressingMode(byte b1, byte b2) {
		if(b2 == (byte)0xF & b1 >= (byte)0x08) {
			switch(b1) {
			case (byte)0x8: return Mode.P8;
			case (byte)0x9: return Mode.P9;
			case (byte)0xA: return Mode.PA;
			case (byte)0xB: return Mode.PB;
			case (byte)0xC: return Mode.PC;
			case (byte)0xD: return Mode.PD;
			case (byte)0xE: return Mode.PE;
			case (byte)0xF: return Mode.PF;
			default: return Mode.NONE;
			}
		} else {
			switch(b1) {
			case (byte)0x4: return Mode.R4;
			case (byte)0x5: return Mode.R5;
			case (byte)0x6: return Mode.R6;
			case (byte)0x7: return Mode.R7;
			case (byte)0x8: return Mode.R8;
			case (byte)0x9: return Mode.R9;
			case (byte)0xA: return Mode.RA;
			case (byte)0xB: return Mode.RB;
			case (byte)0xC: return Mode.RC;
			case (byte)0xD: return Mode.RD;
			case (byte)0xE: return Mode.RE;
			case (byte)0xF: return Mode.RF;
			default: return Mode.NONE;
			}
		}
	}
	
	private String adressingMode(Mode mode, byte b, Operand operand) {
//		byte b1 = (byte)((b >> 4) & (byte)0xf);
		byte b2 = (byte)(b & (byte)0xf);
		
		StringBuilder sb = new StringBuilder(); 
		
		switch(mode) {
		case NONE: //リテラル
			sb.append(String.format("$0x%X", b));
			return sb.toString();
			
		case R4: //インデックス
			display.showBinary();
			buf1 = buf[pos++];
			if((buf1 & 0xF) == (byte)0xF) {
				buf2 = buf[pos++];
				buf3 = buf[pos++];
				buf4 = buf[pos++];
				buf5 = buf[pos++];
				display.showBinary(4);
				num = (int)((buf2 & 0xFF) | ((buf3 & 0xFF) << 8) | ((buf4 & 0xFF) << 16) | ((buf5 & 0xFF) << 24));
				int nextPos = (((buf[pos] & 0x0F) == 0xF) ? pos + 4 : pos) - 32;
				num += nextPos;
				sb.append(String.format("0x%X[%S]", num, readRegister(b2)));
				return sb.toString();
			} else {
				byte buffer1 = (byte)((buf1 >> 4) & (byte)0xf);
				byte buffer2 = (byte)(buf1 & (byte)0xf);
				
				String str = adressingMode(iAdressingMode(buffer1, buffer2), buf1, operand);
				sb.append(String.format("%S[%S]", str, readRegister(b2)));
				return sb.toString();
			}
			
		case R5: //レジスタ
			sb.append(readRegister(b2));
			return sb.toString();
			
		case R6: //レジスタ・ディファード
			sb.append(String.format("(%S)", readRegister(b2)));
			return sb.toString();
			
		case R7: //オートデクリメント
			sb.append(String.format("-(%S)", readRegister(b2)));
			return sb.toString();
			
		case R8: //オートインクリメント
			sb.append(String.format("(%S)+", readRegister(b2)));
			return sb.toString();
			
		case R9: //オートインクリメント・ディファード
			sb.append(String.format("@(%S)+", readRegister(b2)));
			return sb.toString();
			
		case RA: //バイト・ディスプレイスメント
			buf1 = buf[pos++];
			num = buf1;
			//display.showBinary(pos);			
			display.showBinary();
			sb.append(String.format("0x%X(%S)", num, readRegister(b2)));
			return sb.toString();
			
		case RB: //バイト・ディスプレイスメント・ディファード
			buf1 = buf[pos++];
			num = buf1;
			//display.showBinary(pos);			
			display.showBinary();
			sb.append(String.format("@0x%X(%S)", num, readRegister(b2)));
			return sb.toString();
			
		case RC: //ワード・ディスプレイスメント
			buf1 = buf[pos++];
			buf2 = buf[pos++];
			num = (short)((buf1 & 0xFF) + ((buf2 & 0xFF) << 8));
			//display.showBinary(pos, 2);
			display.showBinary(2);
			sb.append(String.format("0x%X(%S)", num, readRegister(b2)));
			return sb.toString();
			
		case RD: //ワード・ディスプレイスメント・ディファード
			buf1 = buf[pos++];
			buf2 = buf[pos++];
			num = (short)((buf1 & 0xFF) + ((buf2 & 0xFF) << 8)); 
			//display.showBinary(pos, 2);
			display.showBinary(2);
			sb.append(String.format("@0x%X(%S)", num, readRegister(b2)));
			return sb.toString();
			
		case RE: //ロングワード・ディスプレイスメント
			buf1 = buf[pos++];
			buf2 = buf[pos++];
			buf3 = buf[pos++];
			buf4 = buf[pos++];
			num = ((buf1 & 0xFF) | ((buf2 & 0xFF) << 8) | ((buf3 & 0xFF) << 16) | ((buf4 & 0xFF) << 24));
			//display.showBinary(pos, 4);
			display.showBinary(4);
			sb.append(String.format("0x%X(%S)", num, readRegister(b2)));
			return sb.toString();
			
		case RF: //ロングワード・ディスプレイスメント・ディファード
			buf1 = buf[pos++];
			buf2 = buf[pos++];
			buf3 = buf[pos++];
			buf4 = buf[pos++];
			num = ((buf1 & 0xFF) | ((buf2 & 0xFF) << 8) | ((buf3 & 0xFF) << 16) | ((buf4 & 0xFF) << 24));
			//display.showBinary(pos, 4);
			display.showBinary(4);
			sb.append(String.format("@0x%X(%S)", num, readRegister(b2)));
			return sb.toString();
		
		/*
		 * プログラム・カウンタ・アドレシング・モード
		 */
			
		case P8: //イミディエイト
			switch(operand.getType()) {
			case BYTE:
				buf1 = buf[pos++];
				//display.showBinary(pos);
				display.showBinary();
				num = buf1 & 0xFF;
				sb.append(String.format("$0x%X", num));
				return sb.toString();
				
			case WORD:
				buf1 = buf[pos++];
				buf2 = buf[pos++];
				//display.showBinary(pos, 2);
				display.showBinary(2);
				num = (buf1 & 0xFF) | ((buf2 & 0xFF) << 8);
				sb.append(String.format("$0x%08X", num));
				return sb.toString();
				
			case LONG:
				buf1 = buf[pos++];
				buf2 = buf[pos++];
				buf3 = buf[pos++];
				buf4 = buf[pos++];
				//display.showBinary(pos, 4);
				display.showBinary(4);
				num = ((buf1 & 0xFF) | ((buf2 & 0xFF) << 8) | ((buf3 & 0xFF) << 16) | ((buf4 & 0xFF) << 24));
				sb.append(String.format("$0x%08X", num));
				return sb.toString();
				
			default:
				System.out.println("\nUNDEFINED");
				return null;
			}
			
		case P9: //アブソリュート
			buf1 = buf[pos++];
			buf2 = buf[pos++];
			buf3 = buf[pos++];
			buf4 = buf[pos++];
			display.showBinary(4);
			num = ((buf1 & 0xFF) | ((buf2 & 0xFF) << 8) | ((buf3 & 0xFF) << 16) | ((buf4 & 0xFF) << 24));
			sb.append(String.format("@0x$%08X", num));
			return sb.toString();
			
		case PA: //バイト・リラティブ
			buf1 = buf[pos++];
			//display.showBinary(pos);
			display.showBinary();
			num = (buf1 & 0xFF) + pos - 32;
			sb.append(String.format("0x%X", num));
			return sb.toString();
			
		case PB: //バイト・リラティブ・ディファード
			buf1 = buf[pos++];
			display.showBinary();
			num = (buf1 & 0xFF) + pos - 32;
			sb.append(String.format("@0x%X", num));
			return sb.toString();
			
		case PC: //ワード・リラティブ
			buf1 = buf[pos++];
			buf2 = buf[pos++];
			//display.showBinary(pos, 2);
			display.showBinary(2);
			num = ((buf1 & 0xFF) | ((buf2 & 0xFF) << 8)) + pos - 32;
			sb.append(String.format("0x%X", num));
			return sb.toString();
			
		case PD: //ワード・リラティブ・ディファード
			buf1 = buf[pos++];
			buf2 = buf[pos++];
			display.showBinary(2);
			num = ((buf1 & 0xFF) | ((buf2 & 0xFF) << 8)) + pos - 32;
			sb.append(String.format("@0x%X", num));
			return sb.toString();
			
		case PE: //ロングワード・リラティブ
			buf1 = buf[pos++];
			buf2 = buf[pos++];
			buf3 = buf[pos++];
			buf4 = buf[pos++];
			//display.showBinary(pos, 4);
			display.showBinary(4);
			num = ((buf1 & 0xFF) | ((buf2 & 0xFF) << 8) | ((buf3 & 0xFF) << 16) | ((buf4 & 0xFF) << 24)) + pos - 32;
			sb.append(String.format("0x%X", num));
			return sb.toString();
			
		case PF: //ロングワード・リラティブ・ディファード
			buf1 = buf[pos++];
			buf2 = buf[pos++];
			buf3 = buf[pos++];
			buf4 = buf[pos++];
			display.showBinary(4);
			num = ((buf1 & 0xFF) | ((buf2 & 0xFF) << 8) | ((buf3 & 0xFF) << 16) | ((buf4 & 0xFF) << 24)) + pos - 32;
			sb.append(String.format("@0x%X", num));
			return sb.toString();
			
		default: 
			System.out.println("UNDEFINED : " + String.format("%02X", b));
			return sb.toString();
		}
	}
	
	private void registOperation() {
		/*
		map.put((byte)0x, new Operation("", new Operand[] { //
				new Operand()
		}));
		*/
		
		map.put((byte)0x00, new Operation("HALT"));
		map.put((byte)0x01, new Operation("NOP"));
		map.put((byte)0x02, new Operation("REI"));
		map.put((byte)0x03, new Operation("BPT"));
		map.put((byte)0x04, new Operation("RET"));
		map.put((byte)0x05, new Operation("RSB"));
		map.put((byte)0x06, new Operation("LDPCTX"));
		map.put((byte)0x07, new Operation("SVPCTX"));
		map.put((byte)0x08, new Operation("CVTPS", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x09, new Operation("CVTSP", new Operand[] { //opcode srclen.rw, srcaddr.ab, dstlen.rw, dstaddr.ab
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x0A, new Operation("INDEX", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x0B, new Operation("CRC", new Operand[] { //opcode tbl.ab, inicrc.rl, strlen.rw, stream.ab,
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x0C, new Operation("PROBER", new Operand[] { //opcode mode.rb, len.rw, base.ab
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x0D, new Operation("PROBEW", new Operand[] { //opcode mode.rb, len.rw, base.ab
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x0E, new Operation("INSQUE", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x0F, new Operation("REMQUE", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x10, new Operation("BSBB", new Operand[] { //
				new Operand(Relative.REL)
		}));
		map.put((byte)0x11, new Operation("BRB", new Operand[] { //
				new Operand(Relative.REL)
		}));
		map.put((byte)0x12, new Operation("BNEQ", new Operand[] { //opcode displ.bb
				new Operand(Relative.REL)
		}));
		map.put((byte)0x13, new Operation("BEQL", new Operand[] { //
				new Operand(Relative.REL)
		}));
		map.put((byte)0x14, new Operation("BGTR", new Operand[] { //
				new Operand(Relative.REL)
		}));
		map.put((byte)0x15, new Operation("BLEQ", new Operand[] { //
				new Operand(Relative.REL)
		}));
		map.put((byte)0x16, new Operation("JSB", new Operand[] { //opcode dst.ab
				new Operand()
		}));
		map.put((byte)0x17, new Operation("JMP", new Operand[] { //opcode displ.bx
				new Operand()
		}));
		map.put((byte)0x18, new Operation("BGEQ", new Operand[] { //
				new Operand(Relative.REL)
		}));
		map.put((byte)0x19, new Operation("BLSS", new Operand[] { //opcode displ.bb
				new Operand(Relative.REL)
		}));
		map.put((byte)0x1A, new Operation("BGTRU", new Operand[] { //opcode displ.bb
				new Operand(Relative.REL)
		}));
		map.put((byte)0x1B, new Operation("BLEQU", new Operand[] { //opcode displ.bb
				new Operand(Relative.REL)
		}));
		map.put((byte)0x1C, new Operation("BVC", new Operand[] { //
				new Operand(Type.BYTE, Relative.REL)
		}));
		
		map.put((byte)0x1E, new Operation("BCC", new Operand[] { //
				new Operand(Relative.REL)
		}));
		map.put((byte)0x1F, new Operation("BLSSU", new Operand[] { //
				new Operand(Type.BYTE, Relative.REL)
		}));
		map.put((byte)0x20, new Operation("ADDP4", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x28, new Operation("MOVC3", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x2B, new Operation("SPANC", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x2E, new Operation("MOVTC", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x30, new Operation("BSBW", new Operand[] { //
				new Operand(Type.WORD, Relative.REL)
		}));
		map.put((byte)0x31, new Operation("BRW", new Operand[] { //
				new Operand(Type.WORD, Relative.REL)
		}));
		map.put((byte)0x32, new Operation("CVTWL", new Operand[] { //opcode src.rx, dst.wy
				new Operand(Type.WORD),
				new Operand(Type.LONG)
		}));
		map.put((byte)0x33, new Operation("CVTWB", new Operand[] { //opcode src.rx, dst.wy
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x34, new Operation("MOVP", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x36, new Operation("CVTPL", new Operand[] { //opcode src.rx, dst.wy
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x38, new Operation("EDITPC", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x3A, new Operation("LOCC", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x3B, new Operation("SKPC", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x3C, new Operation("MOVZWL", new Operand[] { //opcode src.rx, dst.wy : Move Zero-Extended Word to Longword
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x3E, new Operation("MOVAW", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x40, new Operation("ADDF2", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x44, new Operation("MULF2", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x4D, new Operation("CVTWF", new Operand[] { //opcode src.rx, dst.wy
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x50, new Operation("MOVF", new Operand[] { //
				new Operand(Type.LONG),
				new Operand()
		}));
		
		map.put((byte)0x57, new Operation(".WORD", new Operand[] { //
				new Operand(Type.UNDEFINED)
		}));
		
		map.put((byte)0x59, new Operation(".WORD", new Operand[] { //
				new Operand(Type.UNDEFINED)
		}));
		map.put((byte)0x5A, new Operation(".WORD", new Operand[] { //
				new Operand(Type.UNDEFINED)
		}));
		map.put((byte)0x5B, new Operation(".WORD", new Operand[] { //
				new Operand(Type.UNDEFINED)
		}));
		
		map.put((byte)0x5E, new Operation("REMQHI", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x61, new Operation("ADDD3", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x63, new Operation("SUBD3", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x64, new Operation("MULD2", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x65, new Operation("MULD3", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x67, new Operation("DIVD3", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x68, new Operation("CVTDB", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x6C, new Operation("CVTBD", new Operand[] { //opcode src.rx, dst.wy
				new Operand(Type.BYTE),
				new Operand()
		}));
		
		map.put((byte)0x6E, new Operation("CVTLD", new Operand[] { //opcode src.rx, dst.wy
				new Operand(Type.LONG),
				new Operand()
		}));
		
		map.put((byte)0x70, new Operation("MOVD", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x71, new Operation("CMPD", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x72, new Operation("MNEGD", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x74, new Operation("EMODD", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x76, new Operation("CVTDF", new Operand[] { //opcode src.rx, dst.wy
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x77, new Operation(".WORD", new Operand[] { //
				new Operand(Type.UNDEFINED)
		}));
		map.put((byte)0x78, new Operation("ASHL", new Operand[] { //opcode cnt.rb, src.rx, dst.wx
				new Operand(Type.BYTE),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x7E, new Operation("MOVAQ", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x88, new Operation("BISB2", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x89, new Operation("BISB3", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x8A, new Operation("BICB2", new Operand[] { //opcode mask.rx, dst.mx
				new Operand(Type.BYTE),
				new Operand()
		}));
		map.put((byte)0x8B, new Operation("BICB3", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x8C, new Operation("XORB2", new Operand[] { //opcode mask.rx, dst.mx
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x8F, new Operation("CASEB", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x90, new Operation("MOVB", new Operand[] { //opcode src.rx, dst.wx
				new Operand(Type.BYTE),
				new Operand()
		}));
		map.put((byte)0x91, new Operation("CMPB", new Operand[] { //
				new Operand(),
				new Operand(Type.BYTE)
		}));
		map.put((byte)0x92, new Operation("MCOMB", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x93, new Operation("BITB", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		map.put((byte)0x94, new Operation("CLRB", new Operand[] { //
				new Operand()
		}));
		map.put((byte)0x95, new Operation("TSTB", new Operand[] { //
				new Operand()
		}));
		map.put((byte)0x96, new Operation("INCB", new Operand[] { //
				new Operand()
		}));
		
		map.put((byte)0x98, new Operation("CVTBL", new Operand[] { //opcode src.rx, dst.wy
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x9A, new Operation("MOVZBL", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0x9D, new Operation("ACBB", new Operand[] { //opcode limit.rx, add.rx, index.mx, displ.bw
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand(Type.WORD, Relative.REL)
		}));
		map.put((byte)0x9E, new Operation("MOVAB", new Operand[] { //opcode src.ax, dst.wl
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0xA9, new Operation("BISW3", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0xAB, new Operation("XBICW3", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0xAC, new Operation("XOR2", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		map.put((byte)0xAD, new Operation("XORW3", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0xB1, new Operation("CMPW", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0xB5, new Operation("TSTW", new Operand[] { //
				new Operand()
		}));
		
		map.put((byte)0xBA, new Operation("POPR", new Operand[] { //
				new Operand()
		}));
		
		map.put((byte)0xBC, new Operation("CHMK", new Operand[] { //
				new Operand()
		}));
		
		map.put((byte)0xBE, new Operation("CHMS", new Operand[] { //
				new Operand()
		}));
		map.put((byte)0xBF, new Operation("CHMU", new Operand[] { //
				new Operand()
		}));
		map.put((byte)0xC0, new Operation("ADDL2", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		map.put((byte)0xC1, new Operation("ADDL3", new Operand[] { //
				new Operand(Type.LONG),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0xC2, new Operation("SUBL2", new Operand[] { //opcode sub.rx, dif.mx
				new Operand(Type.LONG),
				new Operand()
		}));
		map.put((byte)0xC3, new Operation("SUBL3", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0xC6, new Operation("DIVL2", new Operand[] { //
				new Operand(Type.LONG),
				new Operand()
		}));
		map.put((byte)0xC7, new Operation("DIVL3", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0xC8, new Operation("BISL2", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		map.put((byte)0xC9, new Operation("BISL3", new Operand[] { //opcode mask.rx, src.rx, dst.wx
				new Operand(),
				new Operand(Type.LONG),
				new Operand()
		}));
		map.put((byte)0xCA, new Operation("BICL2", new Operand[] { // : Bit Clear Longword 2operand
				new Operand(Type.LONG),
				new Operand()
		}));
		map.put((byte)0xCB, new Operation("BICL3", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0xCC, new Operation("XORL2", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0xCE, new Operation("MNEGL", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0xD0, new Operation("MOVL", new Operand[] { //opcode src.rx, dst.wx
				new Operand(Type.LONG),
				new Operand()
		}));
		
		map.put((byte)0xD1, new Operation("CMPL", new Operand[] { //opcode src1.rx, src2.rx
				new Operand(),
				new Operand(Type.LONG)
		}));
		
		map.put((byte)0xD4, new Operation("CLRF", new Operand[] { //
				new Operand()
		}));
		map.put((byte)0xD5, new Operation("TSTL", new Operand[] { //opecode src.rx
				new Operand()
		}));
		map.put((byte)0xD6, new Operation("INCL", new Operand[] { //
				new Operand()
		}));
		map.put((byte)0xD7, new Operation("DECL", new Operand[] { //
				new Operand()
		}));
		
		map.put((byte)0xDE, new Operation("MOVAL", new Operand[] { //
				new Operand(),
				new Operand()
		}));
		map.put((byte)0xDD, new Operation("PUSHL", new Operand[] { //opcode src.rl
				new Operand(Type.LONG)
		}));
		
		map.put((byte)0xDF, new Operation("PUSHAL", new Operand[] { //
				new Operand()
		}));
		
		map.put((byte)0xE0, new Operation("BBS", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(Relative.REL)
		}));
		map.put((byte)0xE1, new Operation("BBC", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(Relative.REL)
		}));
		map.put((byte)0xE2, new Operation("BBSS", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(Relative.REL)
		}));
		
		map.put((byte)0xE5, new Operation("BBCC", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(Relative.REL)
		}));
		
		map.put((byte)0xE8, new Operation("BLBS", new Operand[] { //
				new Operand(),
				new Operand(Relative.REL)
		}));
		map.put((byte)0xE9, new Operation("BLBC", new Operand[] { //
				new Operand(),
				new Operand(Relative.REL)
		}));
		
		map.put((byte)0xEC, new Operation("CMPV", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0xEF, new Operation("EXTZV", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0xF1, new Operation("ACBL", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand(Type.WORD, Relative.REL)
		}));
		
		map.put((byte)0xF3, new Operation("AOBLEQ", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(Relative.REL)
		}));
		map.put((byte)0xF4, new Operation("SOBGEQ", new Operand[] { //
				new Operand(),
				new Operand(Relative.REL)
		}));
		map.put((byte)0xF5, new Operation("SOBGTR", new Operand[] { //
				new Operand(),
				new Operand(Relative.REL)
		}));
		map.put((byte)0xF6, new Operation("CVTLB", new Operand[] { //opcode src.rx, dst.wy
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0xF8, new Operation("ASHP", new Operand[] { //
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand(),
				new Operand()
		}));
		map.put((byte)0xF9, new Operation("CVTLP", new Operand[] { //opcode src.rx, dst.wy
				new Operand(),
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0xFB, new Operation("CALLS", new Operand[] { //opcode numarg.rl, dst.ab
				new Operand(),
				new Operand()
		}));
		
		map.put((byte)0xFF, new Operation(".WORD", new Operand[] { //
				new Operand(Type.UNDEFINED)
		}));
	}
	
}
