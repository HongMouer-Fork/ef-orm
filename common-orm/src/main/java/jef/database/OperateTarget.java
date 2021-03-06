package jef.database;

import java.io.StringReader;
import java.lang.reflect.Type;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jef.common.log.LogUtil;
import jef.common.wrapper.IntRange;
import jef.database.Transaction.TransactionFlag;
import jef.database.dialect.DatabaseDialect;
import jef.database.dialect.type.AutoIncrementMapping;
import jef.database.innerpool.IConnection;
import jef.database.innerpool.IManagedConnection;
import jef.database.innerpool.PartitionSupport;
import jef.database.innerpool.ReentrantConnection;
import jef.database.jsqlparser.SqlFunctionlocalization;
import jef.database.jsqlparser.parser.ParseException;
import jef.database.jsqlparser.parser.StSqlParser;
import jef.database.jsqlparser.visitor.SelectItem;
import jef.database.meta.DbProperty;
import jef.database.meta.ITableMetadata;
import jef.database.query.EntityMappingProvider;
import jef.database.query.SqlExpression;
import jef.database.routing.jdbc.SqlExecutionParam;
import jef.database.wrapper.ResultIterator;
import jef.database.wrapper.populator.ResultSetTransformer;
import jef.database.wrapper.populator.Transformer;
import jef.database.wrapper.result.IResultSet;
import jef.database.wrapper.result.MultipleResultSet;
import jef.database.wrapper.result.ResultSetHolder;
import jef.database.wrapper.result.ResultSetImpl;
import jef.database.wrapper.result.ResultSetWrapper;
import jef.tools.MathUtils;
import jef.tools.StringUtils;

/**
 * OperateTarge描述了一个带有状态的数据库操作对象。 这一情况发生在支持路由的多数据源的场合下。
 * 
 * 使用不同的实现类，比如非绑定变量的实现和绑定变量的实现，这样操作的时候无需考虑是否绑定变量，直接用多态来兼容
 * 
 * 从目前的实现来看，CRUD四种操作的绑定变量操作和非绑定操作参数是完全相同的，因此可以用策略模式实现
 * 
 * @author jiyi
 * 
 */
public class OperateTarget implements SqlTemplate {
	private Session session;
	private String dbkey;
	private DatabaseDialect profile;
	
	private ReentrantConnection conn1;

	public String getDbkey() {
		return dbkey;
	}

	public Session getSession() {
		return session;
	}
	
	public Sequence getSequence(AutoIncrementMapping<?> mapping) throws SQLException{
		return session.getNoTransactionSession().getSequenceManager().getSequence(mapping, this);
	}
	

	/**
	 * 获得Sequence对象
	 * @param name 名称
	 * @param len      Sequence长度（位数）
	 * @return sequence
	 * @throws SQLException
	 */
	public Sequence getSequence(String name, int len) throws SQLException{
		return session.getNoTransactionSession().getSequenceManager().getSequence(name,this, len);
	}
	

	public OperateTarget(Session tx, String key) {
		this.session = tx;
		this.dbkey = key;
		this.profile=session.getPool().getProfile(key);
	}

	public SqlProcessor getProcessor() {
		return session.rProcessor;
	}

	public DatabaseDialect getProfile() {
		return profile;
	}

	public DbOperateProcessor getOperator() {
		return session.p;
	}

	/**
	 * 释放连接，不再持有。相当于关闭
	 */
	public void releaseConnection() {
		if (conn1 != null) {
			session.releaseConnection(conn1);
			conn1 = null;
		}
	}
	public String getTransactionId() {
		return this.session.getTransactionId(dbkey);
	}

	public void notifyDisconnect(SQLException e) {
		IConnection conn = getConnection(dbkey);
		if (conn instanceof IManagedConnection) {
			if (getProfile().isIOError(e)) {
				((IManagedConnection) conn).notifyDisconnect();
			}
		}
	}

	public Statement createStatement() throws SQLException {
		return profile.wrap(getConnection(dbkey).createStatement(),isJpaTx());
	}
	public Statement createStatement(int rsType, int concurType) throws SQLException {
		return profile.wrap(getConnection(dbkey).createStatement(rsType, concurType),isJpaTx());
	}
	
	public PreparedStatement prepareStatement(String sql) throws SQLException {
		return profile.wrap(getConnection(dbkey).prepareStatement(sql),isJpaTx());
	}
	
	public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
		return profile.wrap(getConnection(dbkey).prepareStatement(sql, columnNames),isJpaTx());
	}

	public PreparedStatement prepareStatement(String sql, int i) throws SQLException {
		return profile.wrap(getConnection(dbkey).prepareStatement(sql, i),isJpaTx());
	}

	PreparedStatement prepareStatement(String sql, int rsType, int concurType) throws SQLException {
		return profile.wrap(getConnection(dbkey).prepareStatement(sql, rsType, concurType),isJpaTx());
	}

	public CallableStatement prepareCall(String sql) throws SQLException {
		return getConnection(dbkey).prepareCall(sql);
	}

	
	public boolean isResultSetHolderTransaction() {
		if (session instanceof Transaction) {
			Transaction trans = (Transaction) session;
			return TransactionFlag.ResultHolder == trans.innerFlag;
		}
		return false;
	}

	public void commitAndClose(){
		if (session instanceof Transaction) {
			try {
				((Transaction) session).commit();
			} catch (SQLException e) {
				throw DbUtils.toRuntimeException(e);
			}
		}
	}

	IConnection getRawConnection() {
		return getConnection(dbkey);
	}
	
	private ReentrantConnection getConnection(String dbkey2) {
		if (conn1 == null) {
			try {
				conn1 = session.getConnection();
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}
		conn1.setKey(dbkey2);
		return conn1;
	}

	public <T> List<T> populateResultSet(IResultSet rsw,EntityMappingProvider mapping,Transformer transformer) throws SQLException {
		return session.populateResultSet(rsw, mapping, transformer);
	}

	public String getDbName() {
		return session.getDbName(dbkey);
	}

	public int executeSqlBatch(String sql, List<?>... params) throws SQLException {
		return innerExecuteSqlBatch(sql, params);
	}

	final int innerExecuteSqlBatch(String sql, List<?>... params) throws SQLException {
		boolean debug = ORMConfig.getInstance().isDebugMode();
		if (debug)
			LogUtil.show(sql);
		PreparedStatement st = null;
		DbOperateProcessor p = session.p;
		long start = System.currentTimeMillis();
		try {
			st = prepareStatement(sql);
			st.setQueryTimeout(ORMConfig.getInstance().getUpdateTimeout() * 2);// 批量操作允许更多的时间。
			int maxBatchlog=ORMConfig.getInstance().getMaxBatchLog();
			for (int i = 0; i < params.length; i++) {
				StringBuilder sb = debug ? new StringBuilder() : null;
				session.getListener().beforeSqlExecute(sql, params[i]);
				BindVariableContext context = new BindVariableContext(st, this, sb);
				BindVariableTool.setVariables(context, params[i]);
				st.addBatch();
				if (debug) {
					LogUtil.show(sb);
					if (i >= maxBatchlog) {
						debug = false;
					}
				}
				session.checkCacheUpdate(sql, params[i]);
			}
			int[] result = st.executeBatch();
			long dbAccess = System.currentTimeMillis();
			int total = MathUtils.sum(result);
			LogUtil.show(StringUtils.concat("Executed:", String.valueOf(total), "\t Time cost([DbAccess]:", String.valueOf(dbAccess - start), "ms) |", getTransactionId()));
			// 执行回调
			for (int i = 0; i < params.length; i++) {
				session.getListener().afterSqlExecuted(sql, i < result.length ? result[i] : 1, params[i]);
			}
			return total;
		} catch (SQLException e) {
			p.processError(e, sql, this);
			throw e;
		} finally {
			if (st != null)
				st.close();
			releaseConnection();
		}
	}

	public final int innerExecuteSql(String sql, List<Object> ps) throws SQLException {
		Object[] params = ps.toArray();
		DbOperateProcessor p = session.p;

		session.getListener().beforeSqlExecute(sql, params);
		boolean debugMode=ORMConfig.getInstance().isDebugMode();
		long start = System.currentTimeMillis();
		PreparedStatement st = null;
		int total;
		long dbAccess;
		StringBuilder sb = null;
		if (debugMode)
			sb = new StringBuilder(sql).append("\t|").append(this.getTransactionId());
		try {
			st = prepareStatement(sql);
			st.setQueryTimeout(ORMConfig.getInstance().getUpdateTimeout());
			if (!ps.isEmpty()) {
				BindVariableContext context = new BindVariableContext(st, this, sb);
				BindVariableTool.setVariables(context, ps);
			}
			total = st.executeUpdate();
			dbAccess = System.currentTimeMillis();
			if (total > 0) {
				session.checkCacheUpdate(sql, ps);
			}
		} catch (SQLException e) {
			p.processError(e, sql, this);
			throw e;
		} finally {
			if (debugMode)
				LogUtil.show(sb);
			DbUtils.close(st);
			releaseConnection();
		}
		LogUtil.show(StringUtils.concat("Executed:", String.valueOf(total), "\t Time cost([DbAccess]:", String.valueOf(dbAccess - start), "ms) |", getTransactionId()));
		session.getListener().afterSqlExecuted(sql, total, params);
		return total;
	}

	// FIXME sqlRouting 
	public final <T> T innerSelectBySql(String sql, ResultSetTransformer<T> rst, int maxReturn,int fetchSize, List<?> objs) throws SQLException {
		PreparedStatement st = null;
		ResultSet rs = null;
		StringBuilder sb = null;
		DbOperateProcessor p = session.p;
		ORMConfig config=ORMConfig.getInstance();
		boolean debugMode=config.isDebugMode();
		try {
			if (debugMode)
				sb = new StringBuilder(sql.length() + 30 + objs.size() * 20).append(sql).append(" | ").append(this.getTransactionId());
			st = prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
			BindVariableContext context = new BindVariableContext(st, this, sb);
			BindVariableTool.setVariables(context, objs);

			
			st.setQueryTimeout(config.getSelectTimeout());
			if (maxReturn <= 0)
				maxReturn = config.getGlobalMaxResults();
			if (maxReturn > 0)
				st.setMaxRows(maxReturn);
			if(fetchSize<=0)
				fetchSize=config.getGlobalFetchSize();
			if (fetchSize > 0)
				st.setFetchSize(fetchSize);
			rs = st.executeQuery();
			return rst.transformer(rs, getProfile());
		} catch (SQLException e) {
			p.processError(e, sql, this);
			throw e;
		} finally {
			if (debugMode)
				LogUtil.show(sb);
			DbUtils.close(rs);
			DbUtils.close(st);
			releaseConnection();
		}
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	final <T> ResultIterator<T> innerIteratorBySql(String sql, Transformer rst, int maxReturn, int fetchSize, List<Object> objs) throws SQLException {
		long start=System.currentTimeMillis();
		PreparedStatement st = null;
		ResultSet rs = null;
		StringBuilder sb = null;
		ORMConfig config=ORMConfig.getInstance();
		boolean debugMode=config.isDebugMode();
		DbOperateProcessor p = session.p;
		if (debugMode)
			sb = new StringBuilder(sql.length() + 40 + objs.size() * 30).append(sql).append(" | ").append(this.getTransactionId());
		ResultIterator iter;
		long dbAccess;
		try {
			st = prepareStatement(sql);
			st.setQueryTimeout(config.getSelectTimeout());
			
			BindVariableContext context = new BindVariableContext(st, this, sb);
			BindVariableTool.setVariables(context, objs);
			if (maxReturn > 0)
				st.setMaxRows(maxReturn);
			else if (maxReturn > 0)
				st.setMaxRows(maxReturn);
			
			int globalFetchSize=config.getGlobalFetchSize();
			if (fetchSize > 0)
				st.setFetchSize(fetchSize);
			else if (globalFetchSize > 0)
				st.setFetchSize(globalFetchSize);
			rs = st.executeQuery();
			dbAccess = System.currentTimeMillis();
			
			ResultSetWrapper resultset = new ResultSetWrapper(new ResultSetHolder(this,st,rs));
			iter = new ResultIterator.Impl(session.iterateResultSet(resultset, null, rst), resultset);
		} catch (SQLException e) {
			p.processError(e, sql, this);
			throw e;
		} finally {
			if (debugMode)
				LogUtil.show(sb);
		}
		if (debugMode)
			LogUtil.show(StringUtils.concat("Result: Iterator", "\t Time cost([DbAccess]:", String.valueOf(dbAccess - start), "ms) |", getTransactionId()));
		return iter;
	}

	/////////////////////////////////SqlTemplate /////////////////////////
	public <T> NativeQuery<T> createNativeQuery(String sqlString, Class<T> clz){
		return new NativeQuery<T>(this, sqlString, new Transformer(clz));
	}
	

	<T> NativeQuery<T> createNativeQuery(NQEntry nc,Class<T> resultClz){
		return new NativeQuery<T>(this, nc.get(this.profile.getName()),  new Transformer(resultClz));
	}
	public <T> NativeQuery<T> createNativeQuery(String sqlString, ITableMetadata meta){
		NativeQuery<T> q=new NativeQuery<T>(this, sqlString, new Transformer(meta));
		return q;
	}
	
	
	<T> NativeQuery<T> createNativeQuery(NQEntry nc,ITableMetadata meta){
		NativeQuery<T> q= new NativeQuery<T>(this, nc.get(this.profile.getName()), new Transformer(meta));
		return q;
	}
	

	public NativeCall createNativeCall(String procedureName, Type... paramClass) throws SQLException {
		return new NativeCall(this, procedureName, paramClass, false);
	}

	public NativeCall createAnonymousNativeCall(String callString, Type... paramClass) throws SQLException {
		return new NativeCall(this, callString, paramClass, true);
	}

	public <T> NativeQuery<T> createQuery(String jpql, Class<T> resultClass) throws SQLException {
		NativeQuery<T> query = new NativeQuery<T>(this, jpql, new Transformer(resultClass));
		query.setIsNative(false);
		return query;
	}
	
	public <T> PagingIterator<T> pageSelectBySql(String sql, Class<T> clz, int pageSize) throws SQLException {
		return new PagingIteratorSqlImpl<T>(sql, pageSize, new Transformer(clz), this);
	}

	public <T> PagingIterator<T> pageSelectBySql(String sql, ITableMetadata meta, int pageSize) throws SQLException {
		return new PagingIteratorSqlImpl<T>(sql, pageSize, new Transformer(meta), this);
	}
	
//	public int executeJPQL(String jpql) throws SQLException {
//		return executeJPQL(jpql,null);
//	}
	
	public int executeJPQL(String jpql,Map<String,Object> params) throws SQLException {
		NativeQuery<?> nq=this.createQuery(jpql,null);
		if(params!=null){
			nq.setParameterMap(params);
		}
		return nq.executeUpdate();
	}
	
	public <T> List<T> selectByJPQL(String jpql,Class<T> resultClz,Map<String,Object> params) throws SQLException {
		NativeQuery<T> nq=this.createQuery(jpql,resultClz);
		if(params!=null){
			nq.setParameterMap(params);
		}
		return nq.getResultList();
	}
	
	public long countBySql(String countSql, Object... params) throws SQLException {
		long start = System.currentTimeMillis();
		Long num = innerSelectBySql(countSql, ResultSetTransformer.GET_FIRST_LONG, 1,0, Arrays.asList(params));
		if (ORMConfig.getInstance().isDebugMode()) {
			long dbAccess = System.currentTimeMillis();
			LogUtil.show(StringUtils.concat("Count:", String.valueOf(num), "\t [DbAccess]:", String.valueOf(dbAccess - start), "ms) |", getTransactionId()));
		}
		return num;
	}

	public int executeSql(String key, Object... params) throws SQLException {
		return innerExecuteSql(key, Arrays.asList(params));
	}


	public final <T> List<T> selectBySql(String sql, Class<T> resultClz, Object... params) throws SQLException {
		return selectBySql(sql,new Transformer(resultClz),null,params); 
	}
	
	
	public final <T> List<T> selectBySql(String sql, Transformer transformer, IntRange range, Object... params) throws SQLException {
		if (range != null) {
			sql = getProfile().toPageSQL(sql, range);
		}
		long start = System.currentTimeMillis();
		TransformerAdapter<T> sqlTransformer = new TransformerAdapter<T>(transformer,this);
		List<T> list = innerSelectBySql(sql, sqlTransformer, 0, 0,Arrays.asList(params));
		if (ORMConfig.getInstance().isDebugMode()) {
			long dbAccess = sqlTransformer.dbAccess;
			LogUtil.show(StringUtils.concat("Result Count:", String.valueOf(list.size()), "\t Time cost([DbAccess]:", String.valueOf(dbAccess - start), "ms, [Populate]:", String.valueOf(System.currentTimeMillis() - dbAccess), "ms) |", getTransactionId()));
		}
		return list;
	}


	public final <T> T loadBySql(String sql,Class<T> t,Object... params) throws SQLException {
		TransformerAdapter<T> rst=new TransformerAdapter<T>(new Transformer(t),this);
		long start = System.currentTimeMillis();
		List<T> result= innerSelectBySql(sql,rst,2, 0,Arrays.asList(params));
		if (ORMConfig.getInstance().isDebugMode()) {
			long dbAccess = rst.dbAccess;
			LogUtil.show(StringUtils.concat("Result Count:", String.valueOf(result.size()), "\t Time cost([DbAccess]:", String.valueOf(dbAccess - start), "ms, [Populate]:", String.valueOf(System.currentTimeMillis() - dbAccess), "ms) |", getTransactionId()));
		}
		if(result.size()>1){
			throw new IllegalArgumentException("得到多个结果:"+result.size());
		}else if(result.isEmpty()){
			return null;
		}else{
			return result.get(0);
		}
	}

	public final <T> ResultIterator<T> iteratorBySql(String sql, Transformer transformers, int maxReturn, int fetchSize, Object... objs) throws SQLException {
		return innerIteratorBySql(sql, transformers, maxReturn, fetchSize, Arrays.asList(objs));
	}
	
	public DbMetaData getMetaData() throws SQLException {
		return session.getNoTransactionSession().getMetaData(dbkey);
	}
	
	/**
	 * 在这里支持内存混合处理
	 * @author jiyi
	 *
	 * @param <T>
	 */
	public static class TransformerAdapter<T> implements ResultSetTransformer<List<T>> {
		final Transformer t;
		long dbAccess;
		private SqlExecutionParam others;
		private OperateTarget db;

		TransformerAdapter(Transformer t,OperateTarget db) {
			this.t = t;
			this.db=db;
		}

		public List<T> transformer(ResultSet rs, DatabaseDialect profile) throws SQLException {
			dbAccess = System.currentTimeMillis();
			IResultSet irs;
			if(others!=null && others.removedStartWith!=null){
				MultipleResultSet mrs=new MultipleResultSet(false,ORMConfig.getInstance().debugMode);
				mrs.add(rs, null, this.db);
				mrs.setInMemoryConnectBy(others.parseStartWith(mrs.getColumns()));
				irs=mrs.toSimple(null, t.getStrategy());
			}else{
				irs=new ResultSetImpl(rs, profile);
			}
			return db.populateResultSet(irs, null, t);
		}
		
		public SqlExecutionParam getOthers() {
			return others;
		}

		public void setOthers(SqlExecutionParam others) {
			this.others = others;
		}

		public Session getSession(){
			return db.session;
		}
	}

//	final class SqlMapTransformer implements ResultSetTransformer<List<Map<String, Object>>> {
//		long dbAccess;
//
//		public List<Map<String, Object>> transformer(ResultSet rs, DatabaseDialect db) throws SQLException {
//			dbAccess = System.currentTimeMillis();
//			return ResultPopulatorImpl.instance.toVar(new ResultSetImpl(rs, db), Transformer.VAR);
//		}
//	}

	public <T> T getExpressionValue(DbFunction func, Class<T> clz,Object... params) throws SQLException {
		SqlExpression ex=func(func, params);
		return getExpressionValue(ex.toString(), clz);
	}
	
	public <T> T getExpressionValue(String expression, Class<T> clz,Object... params) throws SQLException {
		String sql="select "+expression+" from dual";
		StSqlParser parser = new StSqlParser(new StringReader(sql));
		List<SelectItem> sts;
		try {
			sts = parser.PlainSelect().getSelectItems();
		} catch (ParseException e) {
			throw new SQLException("ParseError:["+sql+"] Detail:"+e.getMessage());
		}
		//进行本地语言转化
		DatabaseDialect dialect=this.profile;
		SqlFunctionlocalization visitor=new SqlFunctionlocalization(dialect,this);
		for(SelectItem item:sts){
			item.accept(visitor);
		}
		//形成语句
		String template=dialect.getProperty(DbProperty.SELECT_EXPRESSION);
		String exps=StringUtils.join(sts, ',');
		if(template==null){
			sql="SELECT "+exps;
		}else{
			sql=String.format(template, exps);
		}
		return this.loadBySql(sql, clz, params);
	}

	public SqlExpression func(DbFunction func, Object... params) {
		return new SqlExpression(getProfile().getFunction(func, params));
	}

	private boolean isJpaTx() {
		return session instanceof Transaction;
	}
	
	public PartitionSupport getPartitionSupport(){
		return session.getPool().getPartitionSupport();
	}

	public OperateTarget getTarget(String database) {
		if(StringUtils.equals(dbkey,database)){
			return this;
		}
		return session.asOperateTarget(database);
	}
}
