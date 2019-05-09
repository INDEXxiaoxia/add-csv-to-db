package tableAdd;
import java.io.FileOutputStream; 
import java.io.IOException; 
import java.nio.charset.Charset; 
import java.util.ArrayList; 
import java.util.List; 
   
import com.csvreader.CsvReader; 
import com.csvreader.CsvWriter; 
public class TestCSV {

	public static void main(String[] args) {
		// TODO Auto-generated method stub
		DataToCSV();
		CSVtoData();

	}
	public static void DataToCSV(){
		String OutFile="C:\\INDEXxiaoxia\\eclipse-workspace\\tableAdd\\CSVfile\\test.csv";
        try {
            // 创建CSV写对象
            CsvWriter csvWriter = new CsvWriter(OutFile,',', Charset.forName("GBK"));
            //CsvWriter csvWriter = new CsvWriter(filePath);

            // 写表头
            String[] headers = {"编号","城市","人口"};
            String[] content = {"1","SYD","5"};
            csvWriter.writeRecord(headers);
            csvWriter.writeRecord(content);
            csvWriter.close();

        } catch (IOException e) {
            e.printStackTrace();
        }	
        System.out.println("TO file SUCCESSFUL");
	}
	public static void CSVtoData() {
		String InFile="C:\\INDEXxiaoxia\\eclipse-workspace\\tableAdd\\CSVfile\\test.csv";;

	        try {
	            // 创建CSV读对象
	            CsvReader csvReader = new CsvReader(InFile);

	            // 读表头
	            csvReader.readHeaders();
	            while (csvReader.readRecord()){
	                // 读一整行
	                System.out.println(csvReader.getRawRecord());
	                // 读这行的某一列
	                System.out.println(csvReader.get(0));
	            }

	        } catch (IOException e) {
	            e.printStackTrace();
	        }
	}

}
