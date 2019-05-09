package tableAdd;

public class soortt {
	
//	排序並決定省會的函數
    public static Cityyyy[] soooort(Cityyyy[] PR) {
//    	按人口排序，并比较两个省会的人口决定省会
    	for(int ii=0;ii<PR.length-1;ii++) {
    		for(int jj=0;jj<PR.length-ii-1;jj++) {
    			if(PR[jj].Citypopulation<PR[jj+1].Citypopulation) {
    				Cityyyy tempCity=PR[jj];
    				PR[jj]=PR[jj+1];
    				PR[jj+1]=tempCity;
    				}
    		}
    	}
//    	將兩個省會存儲在長度為2的數組再進行比較
    	int[] coCity=new int[2];
    	int Cx=0;
    	for(int ii=0;ii<PR.length;ii++) {
    		if(PR[ii].Cityhead==true) {
    			coCity[Cx]=ii;
    			Cx++;
    		}
    	}
    	if(PR[coCity[0]].Citypopulation>PR[coCity[1]].Citypopulation){
    		PR[coCity[1]].Cityhead=false;
    	}
		return PR;
    }
    
    
    


}
