package nl.erdf.datalayer.hbase;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import nl.erdf.datalayer.DataLayer;
import nl.erdf.datalayer.QueryPattern;

import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.graph.Triple;

/**
 * A data layer based on HBase.
 * 
 * The design of the tables is as follows:
 * 
 * We have the data tables and the count tables. The data tables store the
 * values corresponding to a pair of terms, the count tables store the counts.
 * 
 * The data tables consist of the indexed pair of terms, the counter for the
 * term (retrieved from the count tables) and the value.
 * 
 * All tables have a single family, "a" and a single value "v".
 * 
 * @author spyros
 * 
 */
public class HBaseDataLayer implements DataLayer {

	private static final byte[] COUNT_QUALIFIER = "c".getBytes();
	private static final byte[] RESOURCE_QUALIFIER = "r".getBytes();
	private static final byte[] EXISTENCE_QUALIFIER = "e".getBytes();

	private static final byte[] COLUMN = "a".getBytes();

	private static final byte[] EMPTY_BYTEARRAY = new byte[0];

	protected static Logger logger = LoggerFactory
			.getLogger(HBaseDataLayer.class);

	// Data tables
	protected HTable sp_data;
	protected HTable po_data;
	protected HTable so_data;

	// Count tables
	protected HTable sp_counts;
	protected HTable po_counts;
	protected HTable so_counts;

	// All triples table
	protected HTable spo_data;

	protected Random random;

	private HBaseAdmin admin;

	public HBaseDataLayer() throws IOException {
		// Get configuration from the path
		admin = new HBaseAdmin(new HBaseConfiguration());

		initialiseTables();
	}

	/**
	 * @precondition There is only one and only one WILDCARD value
	 */
	@Override
	public long getNumberOfResources(QueryPattern queryPattern) {
		HTable t = getCountTable(queryPattern);

		Get g = new Get(queryPatternToByteArray(queryPattern));
		try {
			Result r = t.get(g);
			byte[] c = r.getValue(COLUMN, COUNT_QUALIFIER);
			if (c == null)
				return 0;
			else {
				long ret = Bytes.toLong(c);
				assert ret > 0 : ret;
				return ret;
			}
		} catch (IOException e) {
			logger.error("Could not get counts", e);
			return -1;
		}
	}

	/**
	 * @pre One and only one WILDCARD in the QueryPattern
	 * @param queryPattern
	 * @return
	 */
	private HTable getCountTable(QueryPattern queryPattern) {
		HTable t;
		if (queryPattern.nodes()[0].equals(QueryPattern.WILDCARD)) {
			t = po_counts;
		} else if (queryPattern.nodes()[1].equals(QueryPattern.WILDCARD)) {
			t = so_counts;
		} else if (queryPattern.nodes()[2].equals(QueryPattern.WILDCARD)) {
			t = sp_counts;
		} else
			throw new IllegalArgumentException(
					"Query pattern must have at least one wildcard");
		return t;
	}

	/**
	 * @pre One and only one WILDCARD in the QueryPattern
	 * @param queryPattern
	 * @return
	 */
	private HTable getDataTable(QueryPattern queryPattern) {
		HTable t;
		if (queryPattern.nodes()[0].equals(QueryPattern.WILDCARD)) {
			t = po_data;
		} else if (queryPattern.nodes()[1].equals(QueryPattern.WILDCARD)) {
			t = so_data;
		} else if (queryPattern.nodes()[2].equals(QueryPattern.WILDCARD)) {
			t = sp_data;
		} else
			throw new IllegalArgumentException(
					"Query pattern must have at least one wildcard");
		return t;
	}

	@Override
	public Resource getRandomResource(Random mersenneTwisterFast,
			QueryPattern queryPattern) {
		try {
			byte[] query = queryPatternToByteArray(queryPattern);

			long numberOfResources = getNumberOfResources(queryPattern);
			if (numberOfResources == 0)
				return null; // Nothing matches this pattern
			// FIXME: optimize: reuse computation
			long index = mersenneTwisterFast.nextInt((int) numberOfResources);

			byte[] queryWithRandom = new byte[query.length + 8];
			System.arraycopy(query, 0, queryWithRandom, 0, query.length);
			Bytes.putLong(queryWithRandom, query.length, index);

			Get g = new Get(queryWithRandom);

			HTable t = getDataTable(queryPattern);

			Result r = t.get(g);
			byte[] c = r.getValue(COLUMN, RESOURCE_QUALIFIER);

			if (logger.isDebugEnabled())
				logger.debug("Getting " + Arrays.toString(queryWithRandom)
						+ " from table "
						+ t.getTableDescriptor().getNameAsString() + " -> "
						+ (c == null ? "null" : Arrays.toString(c)));

			if (c != null)
				return Resource.fromBytes(c);
		} catch (IOException e) {
			logger.error("Could not perform scan", e);
		}
		return null;
	}

	public void insert(Resource sub, Resource pred, Resource obj) {
		try {
			if (logger.isDebugEnabled())
				logger.debug("Inserting: " + sub + " " + pred + " " + obj);

			// FIXME: Can be made more compact by using resourcesToBytes
			byte[] s = sub.toBytes();
			byte[] p = pred.toBytes();
			byte[] o = obj.toBytes();

			byte[] sp = concat(s, p);
			byte[] po = concat(p, o);
			byte[] so = concat(s, o);
			byte[] spo = concat(s, concat(p, o));

			insert(sp, sp_data, sp_counts, o);
			insert(po, po_data, po_counts, s);
			insert(so, so_data, so_counts, p);
			insert(spo, spo_data);

		} catch (IOException e) {
			logger.error("Could not insert triple", e);
		}
	}

	/**
	 * Low-level insert operation for a table. Increments on the count table and
	 * inserts on the data table.
	 * 
	 * @param key
	 * @param table
	 * @param value
	 * @throws IOException
	 */
	protected void insert(byte[] key, HTable data_table, HTable count_table,
			byte[] value) throws IOException {
		long c = count_table.incrementColumnValue(key, COLUMN, COUNT_QUALIFIER,
				1, false) - 1; // Minus one because it returns post-increment
		if (c >= Integer.MAX_VALUE) {
			logger.error("Too many values for key " + Arrays.toString(key)
					+ " for table " + count_table);
		}

		byte[] nKey = new byte[key.length + 8];
		System.arraycopy(key, 0, nKey, 0, key.length);
		Bytes.putLong(nKey, key.length, c);

		Put put = new Put(nKey);
		put.add(COLUMN, RESOURCE_QUALIFIER, value);

		if (logger.isDebugEnabled())
			logger.debug("Inserting " + Arrays.toString(nKey) + " -> "
					+ Arrays.toString(value) + " to table "
					+ data_table.getTableDescriptor().getNameAsString());
		data_table.put(put);
	}

	/**
	 * Low-level insert operation for a table. Only inserts on the data table.
	 * Inserts an empty value
	 * 
	 * @param key
	 * @param table
	 * @param value
	 * @throws IOException
	 */
	protected void insert(byte[] key, HTable data_table) throws IOException {
		Put put = new Put(key);
		put.add(COLUMN, EXISTENCE_QUALIFIER, EMPTY_BYTEARRAY);
		data_table.put(put);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.erdf.datalayer.DataLayer#isValid(com.hp.hpl.jena.graph.Triple)
	 */
	@Override
	public boolean isValid(Triple triple) {
		try {
			Get g = new Get(resourcesToBytes(s, p, o));
			Result res = spo_data.get(g); // FIXME: make sure that an empty byte
											// array is a good value

			return (res.isEmpty() ? Answer.NO : Answer.YES);
		} catch (IOException e) {
			logger.error("Could not check existence for " + s + " , " + p
					+ " , " + o, e);
			return Answer.MAYBE;
		}
	}

	@Override
	public void clear() {
		logger.info("Clearing tables");
		shutdown();
		try {
			disableAndDeleteTable(sp_data);
			disableAndDeleteTable(po_data);
			disableAndDeleteTable(so_data);

			disableAndDeleteTable(sp_counts);
			disableAndDeleteTable(po_counts);
			disableAndDeleteTable(so_counts);

			disableAndDeleteTable(spo_data);

			initialiseTables();

		} catch (IOException e) {
			logger.error("Could not clear tables", e);
		}
		logger.info("Tables cleared");
	}

	private void disableAndDeleteTable(HTable table) throws IOException {
		admin.disableTable(table.getTableName());
		admin.deleteTable(table.getTableName());
	}

	@Override
	public void shutdown() {
		try {
			sp_data.close();
			po_data.close();
			so_data.close();

			sp_counts.close();
			po_counts.close();
			so_counts.close();

			spo_data.close();
		} catch (IOException e) {
			logger.error("could not close tables", e);
			throw new IllegalStateException(e);
		}
	}

	protected void createTable(String tableName) throws IOException {
		HTableDescriptor hTableDescriptor = new HTableDescriptor(tableName);
		hTableDescriptor.addFamily(new HColumnDescriptor(COLUMN));
		admin.createTable(hTableDescriptor);
		logger.info("Created table: " + tableName);
	}

	protected void initialiseTables() throws IOException {
		String[] tableNames = new String[] { "sp_data", "po_data", "so_data",
				"sp_counts", "po_counts", "so_counts", "spo_data" };
		for (String n : tableNames) {
			if (!admin.tableExists(n))
				createTable(n);
		}
		sp_data = new HTable("sp_data");
		po_data = new HTable("po_data");
		so_data = new HTable("so_data");

		sp_counts = new HTable("sp_counts");
		po_counts = new HTable("po_counts");
		so_counts = new HTable("so_counts");

		spo_data = new HTable("spo_data");
		logger.info("Tables initialised");
	}

	/**
	 * Converts a query pattern to a byte array. FIXME: optimise: reduce object
	 * allocations
	 * 
	 * @param pattern
	 * @return
	 */
	protected byte[] queryPatternToByteArray(QueryPattern pattern) {
		Resource[] nodes = pattern.nodes();

		if (nodes[0].equals(QueryPattern.WILDCARD))
			return resourcesToBytes(nodes[1], nodes[2]);
		else if (nodes[1].equals(QueryPattern.WILDCARD))
			return resourcesToBytes(nodes[0], nodes[2]);
		else if (nodes[2].equals(QueryPattern.WILDCARD))
			return resourcesToBytes(nodes[0], nodes[1]);
		else
			throw new IllegalArgumentException(
					"Query pattern has no wildcards: " + pattern);
	}

	/**
	 * @param a
	 * @param b
	 * @return
	 */
	private byte[] concat(byte[] a, byte[] b) {
		byte[] ret = new byte[a.length + b.length];
		System.arraycopy(a, 0, ret, 0, a.length);
		System.arraycopy(b, 0, ret, a.length, b.length);
		return ret;
	}

	/**
	 * @param r
	 * @return
	 */
	private byte[] resourcesToBytes(Resource... r) {
		byte[][] ba = new byte[r.length][];
		int count = 0;

		for (int i = 0; i < ba.length; i++) {
			ba[i] = r[i].toBytes();
			count += ba[i].length;
		}

		byte[] ret = new byte[count];
		count = 0;
		for (int i = 0; i < ba.length; i++) {
			System.arraycopy(ba[i], 0, ret, count, ba[i].length);
			count += ba[i].length;
		}
		return ret;
	}

	/**
	 * Test. Takes as single argument the number of triples to check. IT WILL
	 * DELETE EVERYTHING IN HBASE. It should fail 50% of the time ;-)
	 * 
	 * @param args
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		int count = 1;
		HBaseDataLayer dl = new HBaseDataLayer();

		dl.clear();

		if (args.length > 0) {
			count = Integer.parseInt(args[0]);
		}

		Random r1 = new Random(0);

		// Write values
		for (int i = 0; i < count; i++) {
			Resource s = new URI("http://" + r1.nextLong(), null);
			Resource p = new BNode("_:" + r1.nextLong(), null);
			Resource o = new Literal(r1.nextLong() + "", "no", null);
			Resource o2 = new Literal(r1.nextLong() + "", "no", null);

			dl.insert(s, p, o);
			dl.insert(s, p, o2);
		}

		Random r2 = new Random();
		r1 = new Random(0); // Use the same seed to get the same values

		// Read values
		for (int i = 0; i < count; i++) {
			Resource s = new URI("http://" + r1.nextLong(), null);
			Resource p = new BNode("_:" + r1.nextLong(), null);
			Resource o = new Literal(r1.nextLong() + "", "no", null);

			if (!dl.getRandomResource(r2,
					new QueryPattern(s, p, QueryPattern.WILDCARD)).equals(o)) {
				System.err.println("Retrieval test failed");
			}

			if (!dl.getRandomResource(r2,
					new QueryPattern(QueryPattern.WILDCARD, p, o)).equals(s)) {
				System.err.println("Retrieval test failed");
			}

			if (!dl.getRandomResource(r2,
					new QueryPattern(s, QueryPattern.WILDCARD, o)).equals(p)) {
				System.err.println("Retrieval test failed");
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.erdf.main.datalayer.DataLayer#waitForData()
	 */
	@Override
	public void waitForLatencyBuffer() {
		// Just sleep for a few milliseconds
		try {
			Thread.sleep(10);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
}
