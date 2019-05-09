package tableAdd;


import java.io.IOException; 
import java.nio.charset.Charset;  
import com.csvreader.CsvReader; 
import com.csvreader.CsvWriter; 

public class CSVDB {
//	讀寫文件到數組的兩個函數
	public static void DataToCSV(Cityyyy[] PR){
		String OutFile="C:\\INDEXxiaoxia\\eclipse-workspace\\tableAdd\\CSVfile\\OUT.csv";
        try {
            // 创建CSV写对象
            CsvWriter csvWriter = new CsvWriter(OutFile,',', Charset.forName("GBK"));
            //CsvWriter csvWriter = new CsvWriter(filePath);

            // 写表头
            String[] headers = {"编号","城市","人口"};
            csvWriter.writeRecord(headers);
            for(int ii=0;ii<PR.length;ii++){
            	String[] content= {""+(ii+1),PR[ii].Cityname,""+PR[ii].Citypopulation,""+PR[ii].Cityhead};
                csvWriter.writeRecord(content);
//                System.out.println(ii);
            }
            csvWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }	
        System.out.println("數據寫入 OUT.csv 成功...");
	}
	
	
	public static Cityyyy[] CSVtoData(String filename) {
		Cityyyy[] PRread=new Cityyyy[10];
		String InFile="C:\\INDEXxiaoxia\\eclipse-workspace\\tableAdd\\CSVfile\\"+filename;
	        try {
	            // 创建CSV读对象
	            CsvReader csvReader = new CsvReader(InFile);

	            // 读表头
	            csvReader.readHeaders();
	        	System.out.println(filename+"中讀取的數據：");
	        	int ii=0;
	            while (csvReader.readRecord()){         	
	                // 读一整行
//	                System.out.println(csvReader.getRawRecord());
	                // 读这行的几列           存入數組 
	                System.out.println(csvReader.get(0)+"\t"+csvReader.get(1)+"\t"+csvReader.get(2)+"\t"+csvReader.get(3));
	                try {
	                	int Cityid = Integer.parseInt(csvReader.get(0));
	                    int Citypopulation = Integer.parseInt(csvReader.get(2));
	                    boolean Cityhead = 	Boolean.parseBoolean(csvReader.get(3));
//	                    System.out.println(Cityid+"-"+"-"+Citypopulation+"-"+Cityhead);
	                    PRread[ii]=new Cityyyy(csvReader.get(1),Citypopulation,Cityhead);
//		                PRread[ii].Cityid=Cityid;
//		                PRread[ii].Cityname=csvReader.get(1);
//		                PRread[ii].Citypopulation=Citypopulation;
//		                PRread[ii].Cityhead=Cityhead;
	                } catch (NumberFormatException e) {
	                    e.printStackTrace();
	                }
	                ii++;
	            }
	        } catch (IOException e) {
	            e.printStackTrace();
	        } 
	        System.out.println(filename+"讀取到數組完成...");
	        return PRread;
	}

}
