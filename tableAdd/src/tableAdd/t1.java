package tableAdd;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
public class t1 {
    public static void main(String[] args) {

    	Connection con;
    	String driver = "com.mysql.cj.jdbc.Driver";
    	String url = "jdbc:mysql://localhost:3306/xx1?useSSL=false";
    	String user = "root";
    	String password = "!821480-Xx1";
    	
    	try {
        	//注册驱动
			Class.forName(driver);
			//获取连接对象
			con = DriverManager.getConnection(url,user,password);
			//创建SQL执行对象
			Statement statement = con.createStatement();		
//			-----------------------------------------------------------------------
//			主程序部分
			
			//從文件中讀取數據到數組
			Cityyyy[] PRreadA=CSVDB.CSVtoData("PAin.csv");
			Cityyyy[] PRreadB=CSVDB.CSVtoData("PBin.csv");
			//將數組中的數據插入庫
			dataToDB(PRreadA,statement,"pa");
			dataToDB(PRreadB,statement,"pb");			
			
//			查询第一个表
			System.out.println("第一张表數據：");
			ResultSet rs=SqlCon(statement,"select * from pa order by population DESC;");	
	    	int i =0;
			Cityyyy[] PR=new Cityyyy[PRreadA.length+PRreadB.length];//長度為兩個表數據綜合
//          获取数据，存入数组
			i=TabToArr(rs,i,PR);//獲取下一個i的值
//			查询第二个表
			System.out.println("第二张表數據：");
			ResultSet rs1=SqlCon(statement,"select * from pb order by population DESC;");	
			TabToArr(rs1,i,PR);
			
			PR=soortt.soooort(PR);//執行排序合併到數組
			System.out.println("兩數組合併結果：");
			for(int ii=0;ii<PR.length-1;ii++) {
				System.out.println(PR[ii].Cityname+"\t"+PR[ii].Citypopulation+"\t"+PR[ii].Cityhead);
			}
//			將合併後的數組插入DB
			dataToDB(PR,statement,"pZone");
//			將合併後的數據寫入CSV
			CSVDB.DataToCSV(PR);

//			---------------------------------------------------------------------
			rs.close();
			con.close();

		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
 
    }
    public static int TabToArr(ResultSet rs,int i,Cityyyy[] PR) throws SQLException {
//    	根据结果集获取数据，并存入数组
		while(rs.next()) {
			String name = rs.getString("name");
			int population = rs.getInt("population");
			boolean head = rs.getBoolean("head");
			int populationx=Integer.valueOf(population).intValue();
			Cityyyy CityX=new Cityyyy(name,populationx,head);
			System.out.println(CityX.Cityname+"\t"+CityX.Citypopulation+"\t"+CityX.Cityhead);
			PR[i]=CityX;
			i++;
		
		}
		return i;
    }
    public static ResultSet SqlCon(Statement statement,String sql) throws SQLException {
		statement.execute(sql);
		//结果集存放
		ResultSet rs = statement.executeQuery(sql);
		return rs;
    }

    public static void dataToDB(Cityyyy[] PR,Statement statement,String tablename){
//    	將數組中的數據存入數據庫
    	for(int ii=0;ii<PR.length;ii++) {
    		int iii=ii+1;
    		String sql=""
    				+ "insert into "+tablename+" values "
    				+ "("+iii+",'"+PR[ii].Cityname+"',"+PR[ii].Citypopulation+","+PR[ii].Cityhead+")";
    		try {
				statement.execute(sql);
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

    	}
		System.out.println("數組中數據插入表"+tablename+"成功...");
    }
}
