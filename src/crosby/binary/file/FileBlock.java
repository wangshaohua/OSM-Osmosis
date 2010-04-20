package crosby.binary.file;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import com.google.protobuf.ByteString;

import crosby.binary.Fileformat;
import crosby.binary.Fileformat.Blob;
import crosby.binary.Fileformat.FileBlockHeader;
import crosby.binary.Fileformat.FileBlockHeader.Builder;

public class FileBlock extends FileBlockBase {
	/** Contains the contents of a block for use or further processing */
	ByteString data; // serialized Format.Blob 

	private FileBlock(String type, ByteString blob, ByteString indexdata) {
		super(type,indexdata);
		this.data = blob;
	}

	public static FileBlock newInstance(String type, ByteString blob, ByteString indexdata) {
		return new FileBlock(type,blob,indexdata);
	}
	public static FileBlock newInstance(String type, ByteString indexdata) {
		return new FileBlock(type,null,indexdata);
	}

	protected void deflateInto(crosby.binary.Fileformat.Blob.Builder blobbuilder) {
		int size = data.size();
		Deflater deflater = new Deflater();
		deflater.setInput(data.toByteArray());
		deflater.finish();
		byte out[] = new byte[size];
		deflater.deflate(out);

		if (!deflater.finished()) {
			// Buffer wasn't long enough. Be noisy.
			System.out.println("Compressed buffer too short causing extra copy");
			out = Arrays.copyOf(out, size+size/64+16);
			deflater.deflate(out, deflater.getTotalOut(), out.length-deflater.getTotalOut());
			assert(deflater.finished());
		}
		ByteString compressed = ByteString.copyFrom(out,0,deflater.getTotalOut());
		blobbuilder.setZlibData(compressed);	
		deflater.end();
	}
	public FileBlockReference writeTo(DataOutputStream outwrite, CompressFlags flags) throws IOException {
		Fileformat.FileBlockHeader.Builder builder = Fileformat.FileBlockHeader.newBuilder();
		if (indexdata != null)
			builder.setIndexdata(indexdata);
		builder.setType(type);

		Fileformat.Blob.Builder blobbuilder = Fileformat.Blob.newBuilder();
		if (flags == CompressFlags.NONE) {
			blobbuilder.setRaw(data);
		} else {
			blobbuilder.setRawSize(data.size());
			if (flags == CompressFlags.DEFLATE)
				deflateInto(blobbuilder);
			else
				assert false : "TODO"; // TODO
		}	
		Fileformat.Blob blob = blobbuilder.build();

		builder.setDatasize(blob.getSerializedSize());
		Fileformat.FileBlockHeader message = builder.build();
		int size = message.getSerializedSize();

		//System.out.format("Outputed header size %d bytes, header of %d bytes, and blob of %d bytes\n",
		//		size,message.getSerializedSize(),blob.getSerializedSize());
		outwrite.writeInt(size);
		long offset = -1; // TODO: Need to get the real offset;
		message.writeTo(outwrite); 		
		blob.writeTo(outwrite);
		return FileBlockReference.newInstance(this,offset,size);
	}

	/** Reads or skips a fileblock. */
	static void process(DataInputStream input, BlockReaderAdapter callback) throws IOException {
		FileBlockHead fileblock = FileBlockHead.readHead(input);
		if (callback.skipBlock(fileblock)) {
			//System.out.format("Attempt to skip %d bytes\n",header.getDatasize());
			fileblock.skipContents(input);
		} else {
			callback.handleBlock(fileblock.readContents(input));
		}
	}

	public ByteString getData() {
		return data;
	}
}
