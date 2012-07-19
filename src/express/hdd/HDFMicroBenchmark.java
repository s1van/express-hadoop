package express.hdd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;

import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileAsTextInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * Test HDF-MR data generated by HDFGen.
 * The user specifies the size of chunks to read. 
 * The format of the data is:
 * <ul>
 * <li>(x1,x2,...) (l1,l2,...) (data of a record size) \r \n
 * </ul>
 *
 * <p>
 * To run the program: 
 * <b>bin/hadoop jar express-hadoop.jar express.hdd.HDFMicroBenchmark [dataSize] [chunkOffset] [chunkSize] [inDir] [outDir] [enableHDF] [enableByPass] [singleReducer]</b>
 * e.g.
 * <b>bin/hadoop jar express-hadoop.jar express.hdd.HDFMicroBenchmark 32,64,128 0,0,0 32,64,16 hdf-test hdf-test-out 'true'</b>
 */

public class HDFMicroBenchmark extends Configured implements Tool{
	
	public static class HDFMicroMapper extends MapReduceBase 
    implements Mapper<Text, Text, Text, Text> {
		
		protected FileSystem fs;

		private static int [] dataSize;
		private static int [] coffset;
		private static int [] clength;
		private static int dimension;
		private static HyperRectangleData recData;

		@SuppressWarnings("unused")
		private static Path InputDir;
		@SuppressWarnings("unused")
		private JobConf job;
		
		
		public void configure(JobConf job) {
		     try {
		    	 dataSize = Tools.StringArray2IntArray(job.get("DataSize").toString().split(","));
		    	 coffset = Tools.StringArray2IntArray(job.get("ChunkOffset").toString().split(","));
		    	 clength = Tools.StringArray2IntArray(job.get("ChunkLength").toString().split(","));
			     InputDir=  new Path(job.get("InputDirectory").toString());
			     
			     dimension = dataSize.length;
			     Integer.parseInt(job.get("NumberOfNodes"));
			     this.job=job;
			     
			     recData = new HyperRectangleData(dataSize, coffset, clength, dimension);  
			     fs = FileSystem.get(job);
			     //System.out.println("HDFMicroBenchmark::mapper:configure()");
		     } catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		}
		
	    public void map(Text key, Text value,
              OutputCollector<Text, Text> fout,
              Reporter reporter) throws IOException {
	    	
			//Pass locality info to JobTracker
			//reporter.setStatus(key.toString());
	    	
	    	int[] offset = {}; 
	    	int[] length = {};
	    	try {
				Pair<int[], int[]> keyPair = Tools.text2Pair(key);
				offset = keyPair.getLeft();
				length = keyPair.getRight();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace(); 
			}
			Iterator<int[]> chunkOffsetIterator = recData.iterator();
			int[] aboffset = {};
			while(chunkOffsetIterator.hasNext()){
				int[] itrOffset = chunkOffsetIterator.next(); 
				Pair<int[], int[]> outputPair = HyperRectangleData.getHyperRectangleIntersection(offset,
						length, itrOffset, clength,dimension);
				if (outputPair == null)
					continue;
				aboffset = outputPair.getLeft();
				int[] roffset = HyperRectangleData.getRelativeOffset(offset, aboffset,dimension);
				int[] rlength = outputPair.getRight();
				byte[] buffer = new byte[HyperRectangleData.getVolume(rlength)];
				HyperRectangleData.getChunkOfHighDimData(value.getBytes(), length, dimension, buffer, roffset,rlength);
				
				try {
					Text fkey = Tools.pair2Text(itrOffset, recData.getChunkLength()); //final key
					Text fvalue = Tools.pair2Text(aboffset, rlength); // final value = relative key + value
					fvalue.append(buffer, 0, buffer.length); // fvalue = fvalue buffer
					fout.collect(fkey, fvalue);
					System.out.printf("fkey = %s, rkey = %s, rlength = %s, value has length of %d\n", 
			    			fkey.toString(), Arrays.toString(aboffset), Arrays.toString(rlength), fvalue.getLength());
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}	
			}
	    	//System.out.printf("key = %s, offset = %s, length = %s, value has length of %d\n", 
	    	//		key.toString(), Arrays.toString(offset), Arrays.toString(length), value.getLength());
	    }
	  }
	
	public static class HDFMicroReducer extends MapReduceBase implements Reducer<Text, Text, Text, Text>{

		protected FileSystem fs;
		private JobConf job;

		private static int [] dataSize;
		private static int [] coffset;
		private static int [] clength;
		private static int dimension;
		private static HyperRectangleData recData;
		private static Path OutputDir;
		private static boolean isWriter;
		private static int waitSecs;
		private static boolean isWait;
		
		public void configure(JobConf job) {
		     try {
		    	 fs = FileSystem.get(job);
		    	 this.job = job;
		    	 OutputDir=  new Path(job.get("OutputDirectory").toString());
		    	 isWriter = job.getBoolean("hdf.reduce.write", false);
		    	 dataSize = Tools.StringArray2IntArray(job.get("DataSize").toString().split(","));
		    	 coffset = Tools.StringArray2IntArray(job.get("ChunkOffset").toString().split(","));
		    	 clength = Tools.StringArray2IntArray(job.get("ChunkLength").toString().split(","));
			     
			     dimension = dataSize.length;
			     Integer.parseInt(job.get("NumberOfNodes"));
			     waitSecs = Integer.parseInt(job.get("hdf.reduce.wait"));
			     if (waitSecs > 0)
			    	 isWait = true;
			     else
			    	 isWait = false;
			     
			     recData = new HyperRectangleData(dataSize, coffset, clength, dimension);  			     //System.out.println("HDFMicroBenchmark::mapper:configure()");
		     } catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		}
		
		@Override
		public void reduce(Text key, Iterator<Text> values,
				OutputCollector<Text, Text> fout, Reporter arg3)
				throws IOException {
			
			int[] offset = {}; 
	    	int[] length = {};
	    	try {
				Pair<int[], int[]> keyPair = Tools.text2Pair(key);
				offset = keyPair.getLeft();
				length = keyPair.getRight();
			
				//byte[] buffer = new byte[recData.getChunkSize() + 8192];
				//ByteBuffer target = ByteBuffer.wrap(buffer);
				int itr = 0; int vsize = 0;
				Path reducerFile = new Path(OutputDir, Integer.toString(recData.getChunkNumber(keyPair.getLeft())) );
				final SequenceFile.Writer writer = SequenceFile.createWriter(fs, job
			    			, reducerFile, key.getClass(), Text.class, CompressionType.NONE);
				while (values.hasNext()){
					Text value = values.next();
					int keyEndOffset = value.find("]", value.find("]")+1); //find the second ']' sincce key looks like[];[]
					Text outputKey = new Text(Arrays.copyOfRange(value.getBytes(), 0, keyEndOffset+1));
					Pair<int[], int[]> output_key = Tools.text2Pair(outputKey);
						
					itr++;
					//vsize = vsize + value.getLength();
					//byte[] ready2dump = value.getBytes();
					//int vlength = ready2dump.length - 32;
					//target.put(ready2dump, 32, vlength);
					if (isWriter) {
						writer.append(outputKey, value);	
					}
					System.out.printf("key = %s, outputKey = %s, offset = %s, length = %s, %d values with total size %d\n", 
			    			key.toString(), outputKey.toString(), Arrays.toString(offset), 
			    			Arrays.toString(length), itr, vsize);
				}
				if (isWait)
						Thread.sleep(waitSecs * 1000);
				writer.close();		
			

			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace(); 
			}
		}
		
	}

	
	
	
	@Override
	public int run(String[] args) throws Exception {
		JobConf job = (JobConf) getConf();
		final FileSystem fs = FileSystem.get(job);
		DistributedFileSystem dfs = (DistributedFileSystem)fs;
		int nodeAmount = dfs.getDataNodeStats().length; 
		
		job.set("DataSize", args[0]);
		job.set("PartitionOffset", args[1]);
		job.set("PartitionRecordLength", args[2]);
		job.set("PartitionSize", args[3]);
		job.set("ChunkOffset", args[4]);
		job.set("ChunkLength", args[5]);
		job.set("InputDirectory", args[6]);
		job.set("OutputDirectory", args[7]);
		job.set("NumberOfNodes", Integer.toString(nodeAmount));
		job.setBoolean("isHighDimensionData", new Boolean(args[8]));
		if (args.length >= 10)
			job.setBoolean("hdf.reduce.bypass", new Boolean(args[9]));
		if (args.length >= 11)
			job.setBoolean("hdf.reduce.one", new Boolean(args[10]));
		
		
		HyperRectangleData data = new HyperRectangleData(
				Tools.getHDFVectorFromConf(job, "DataSize"),
				Tools.getHDFVectorFromConf(job, "ChunkOffset"), 
				Tools.getHDFVectorFromConf(job, "ChunkLength"),
				Tools.getHDFVectorFromConf(job, "DataSize").length);
		//System.out.println("getBoolean: " + job.getBoolean("isHighDimensionData", false));
		
		job.setJobName("HDFMicroBenchmark");
	    job.setJarByClass(HDFMicroBenchmark.class);
	    job.setMapperClass(HDFMicroMapper.class);
	    job.setReducerClass(HDFMicroReducer.class);
	    job.setOutputKeyClass(Text.class);
	    job.setOutputValueClass(Text.class);
	    job.setPartitionerClass(HDFPartitioner.class);
	    if (job.getBoolean("hdf.reduce.bypass", false))
	    	job.setNumReduceTasks(0);
	    else if (job.getBoolean("hdf.reduce.one", false))
	    	job.setNumReduceTasks(1);
	    else
	    	job.setNumReduceTasks(data.getNumOfChunks());
	    
	    job.setInputFormat(SequenceFileAsTextInputFormat.class);
	    job.setSpeculativeExecution(false);
	    
	    final Path inDir = new Path(args[6]);
	    final Path outDir = new Path(args[7]);
	    FileInputFormat.setInputPaths(job, inDir);
	    FileOutputFormat.setOutputPath(job, outDir);
		
		JobClient.runJob(job);    
		return 0;
	}

	public static void main(String[] args) throws Exception {
		//HyperRectangleData.test();
		//HyperRectangleData.TestGetChunkOffsetByNumber();
		//HyperRectangleData.TestGetOverlappedChunks();
		//Tools.TestOperation4SortedArray();
		int res = ToolRunner.run(new JobConf(), new HDFMicroBenchmark(), args);
	    System.exit(res);
	}
}
