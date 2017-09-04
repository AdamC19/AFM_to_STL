import ij.*;
import ij.io.*;
import ij.process.*;
import ij.gui.*;
import java.awt.*;
import ij.plugin.filter.*;
import java.io.*;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AFM_to_STL
 * 
 * This Plugin Filter takes an image and produces an STL file suitable for 3d
 * printing, where pixels of higher intensity result in higher features on the
 * model. The program first copies the image, then converts it to 8-bit 
 * grayscale, and finally writes ASCII text to a file specified by the user.
 * The resulting STL file is in ASCII, not binary, and so could be fairly
 * large. The expected number of facets can be predicted by the following
 * equation
 * 
 * Facets = 4XY  +  2( X + Y )  -  10
 * 
 * where X and Y describe the width and height of the image, respectively.
 * 
 * @author Adam Cordingley
 * @version 25-July-2016
 * Copyright (c) Mike Norton, Adam Cordingley 2016
 */
public class AFM_to_STL implements PlugInFilter {
	ImagePlus imp;
	ImagePlus gray;
	ImageProcessor ip;
	ImageProcessor ip_gray;
	PrintWriter out;

	/* Number of bytes to define a facet in a binary STL file. 
	each facet = 12 * (REAL32) + UINT16  = 50 bytes */
	public final int BYTES_PER_FACET = 50;

	/* Number of bytes in the header of a binary STL file.
	header = (UINT8 * 80) + UINT32 = 84 bytes*/
	public final int BYTES_IN_HEADER = 84;

	private double dScanSizeNM, dSamplesPerLine, dLines, nmPerSample, dZScaleFactor, dBasethickness;
	private String sTitle 		= "AFM_to_STL_test1";
	private String sDescription = "This is an ImageJ Plugin designed to convert an AFM scan image to an STL suitable for 3D printing.";
	private String sModelName;
	private int nLenY, nLenX, nFacets;
	private boolean isBinary = false;
	
	/**
	 * Vertex holds values for actual (x,y,z) coordinates of the vertices.
	 * Includes accessor and mutator methods for each dimension.
	 * This class must implement the Cloneable interface and catch 
	 * CloneNotSupportedException. It must also override the Object.clone()
	 * method and make it public. This is in order to allow for making a 
	 * clone (shallow copy) of Vertex objects. 
	 */
	private class Vertex implements Cloneable{
		private double x,y,z;	//store actual coordinates
		public Vertex(double x, double y, double z){
			this.x = x;
			this.y = y;
			this.z = z;
		}
		public double getX(){return x;}
		public double getY(){return y;}
		public double getZ(){return z;}
		public void setX(double x){this.x = x;}
		public void setY(double y){this.y = y;}
		public void setZ(double z){this.z = z;}
		
		public Object clone(){
			try{
				return super.clone();
			}catch(CloneNotSupportedException e){
				return null;
			}
			
		}
	}
	
	/** Method to return types supported
     * @param arg unused
     * @param imp The ImagePlus, used to get the spatial calibration
     * @return Code describing supported formats etc.
     * (see ij.plugin.filter.PlugInFilter & ExtendedPlugInFilter)
     */
	public int setup(String arg, ImagePlus imp) {
		
		if(arg.equalsIgnoreCase("about")){
			showAbout();
			return DONE;
		}
		this.imp = imp;
		return DOES_ALL+NO_CHANGES;
	}

	public void run(ImageProcessor ip) {
		this.ip = ip;
		nLenY = ip.getHeight(); 
		nLenX = ip.getWidth();
		
		//CODE TO MAKE A COPY THAT IS GRAYSCALE
		gray = NewImage.createByteImage("Copy_of_"+imp.getTitle(), nLenX, nLenY, 1, NewImage.FILL_BLACK);
		ip_gray = gray.getProcessor();
		ip_gray.copyBits(ip,0,0,Blitter.COPY);
		
		nLenY = ip_gray.getHeight();
		nLenX = ip_gray.getWidth();
		
		if(!showDialog()){
			return;
		}
		//
		GenericDialog gdInfo;
		if(saveAsStl()){
			gdInfo = new GenericDialog("Model Statistics");
			int nExpected = 4*nLenX*nLenY + 2*(nLenX + nLenY) - 10;
			gdInfo.addMessage("Expected "+ nExpected +" facets");
			gdInfo.addMessage("Model has "+ nFacets +" facets.");
			
		}else{
			gdInfo = new GenericDialog("Summary");
			gdInfo.addMessage("No valid file path given. AFM to STL will exit.");
		}
		gdInfo.showDialog();
	}
	
	/**
	 * Method that gives the user a SaveDialog
	 * 
	 * @return false if no file location is selected by the user, otherwise
	 * control is passed to saveAsStl(java.lang.String) and the result of it is
	 * returned. 
	 */
	public boolean saveAsStl(){
		SaveDialog sd = new SaveDialog("Save ASCII output file ",sModelName,".stl");
		String sBase = sd.getDirectory();
		String sFileName = sd.getFileName();
		if (sFileName == null){
			return false;
		}else{
			if(!isBinary)
				return saveAsStl(sBase+sFileName);
			else
				return saveAsBinary(sBase+sFileName);
		}
	}
	
	/**
	 * Method that does the actual work of writing to the output file.
	 * 
	 * @param sPath String that descirbes where to write the file.
	 * 
	 * The method works by first putting the image pixel values into a 1D array.
	 * Next it creates a 2D array of Vertex objects. It then iterates several 
	 * times through the array and writes STL formatted data to the output file.
	 * 
	 * The 2D array (refered to as the expanded array) has Vertices that are 
	 * "imaginary", in that they are manufactured based on the actual data from
	 * the image, but do not represent an actual point in the image. 
	 * 
	 * real point:		o
	 * imaginary point:	+
	 * 							o		o		o		o
	 *
	 * 								+		+		+
	 * 
	 * 							o		o		o		o
	 * 
	 * The height, z, of this imaginary point is an average of its four 
	 * neighbors and its x & y coordinates place it in the center between them.
	 * This point is used in creating facets and allows a more accurate 3D
	 * representation of the surface.
	 */
	public boolean saveAsStl(String sPath){
		
		try{
			out = new PrintWriter(sPath);
			byte[] pixels = (byte[])ip_gray.getPixels();	//make a big 1D array
															//values ranging from -128 to 127
			short[] rawHeights = new short[pixels.length];	//big 1D array
			
			int nLowest = 255;
			
			//FIND LOWEST HEIGHT VALUE & MAKE COPY ARRAY
			for(int pix = 0; pix < pixels.length; pix++){
				rawHeights[pix] = (short)(pixels[pix] & 0xff);	//make values range from 0-255 shrtPix;
				if(rawHeights[pix] < nLowest){
					nLowest = rawHeights[pix];
				}
				
			}
			
			//MAKE THE ARRAY FOLLOW THE RIGHT-HAND-RULE
			Vertex vertices[][] = new Vertex[nLenY][nLenX];	//nLenY rows of nLenX points each
			int nLoc = rawHeights.length;
			double height;
			for(int row = 0; row < nLenY; row++){
				for(int pt = nLenX-1; pt >= 0; pt--){
					height = (( (rawHeights[--nLoc]) - nLowest ) * dZScaleFactor) + dBasethickness;
					Vertex v = new Vertex(pt*nmPerSample,row*nmPerSample,height);
					vertices[row][pt] = v;
				}
			}
			//MAKE EXPANDED ARRAY
			Vertex expHeights[][] = new Vertex[(nLenY*2)-1][];//nLenX];
			for(int row = 0; row < nLenY; row++){
				expHeights[row*2] = Arrays.copyOf(vertices[row], nLenX);
				Vertex[] newRow = new Vertex[nLenX-1];
				
				if(row < nLenY-1){
					for(int pt = 0; pt < nLenX-1; pt++){
						Vertex v 		= vertices[row][pt];
						Vertex nextV	= vertices[row][pt+1];
						Vertex v2		= vertices[row+1][pt];
						Vertex nextV2	= vertices[row+1][pt+1];
						
						double newX	= v.getX() + (nmPerSample/2);
						double newY	= v.getY() + (nmPerSample/2);
						double newZ	= (v.getZ()+nextV.getZ()+v2.getZ()+nextV2.getZ())/4;
						
						Vertex newV = new Vertex(newX, newY, newZ);
						newRow[pt] = newV;
					}	
					expHeights[(row*2)+1] = newRow;
				}
			}
			//BEGIN STL FILE
			out.print("solid "+ sModelName + "\n");
			
			//MAKE THE FLOOR
			for(int line = 0; line<expHeights.length; line++){
				if(line == 0){
					for(int pt = 0; pt < nLenX-1; pt++){
						Vertex va = (Vertex) expHeights[line][pt].clone();
						Vertex vb = (Vertex) expHeights[line+2][nLenX-1].clone();
						Vertex vc = (Vertex) expHeights[line][pt+1].clone();
						va.setZ(0.0);
						vb.setZ(0.0);
						vc.setZ(0.0);
						
						out.print(makeFacet(va,vb,vc));
					}
				}else if(line == expHeights.length-1){
					for(int pt = 0; pt < nLenX-1; pt++){
						Vertex va = (Vertex) expHeights[line][pt].clone();
						Vertex vb = (Vertex) expHeights[line][pt+1].clone();
						Vertex vc = (Vertex) expHeights[line-2][0].clone();
						va.setZ(0.0);
						vb.setZ(0.0);
						vc.setZ(0.0);
						
						out.print(makeFacet(va,vb,vc));
					}
				}else if(line % 2 == 0){
					Vertex va = (Vertex) expHeights[line][0].clone();
					Vertex vb = (Vertex) expHeights[line][nLenX-1].clone();
					Vertex vc = (Vertex) expHeights[line-2][0].clone();
					va.setZ(0.0);
					vb.setZ(0.0);
					vc.setZ(0.0);
					out.print(makeFacet(va,vb,vc));
					
					Vertex va2 = (Vertex) expHeights[line][0].clone();
					Vertex vb2 = (Vertex) expHeights[line+2][nLenX-1].clone();	//NullPointerException!!!
					Vertex vc2 = (Vertex) expHeights[line][nLenX-1].clone();
					va2.setZ(0.0);
					vb2.setZ(0.0);
					vc2.setZ(0.0);
					out.print(makeFacet(va2,vb2,vc2));
				}else{}
			}
			
			//MAKE EDGES / SIDES
			for(int line = 0; line<expHeights.length; line++){
				if(line == 0){	//front
					for(int pt = 0; pt < nLenX-1; pt++){
						Vertex va = expHeights[line][pt+1];
						Vertex vb = (Vertex) expHeights[line][pt].clone();
						vb.setZ(0.0);
						Vertex vc = (Vertex) va.clone();
						vc.setZ(0.0);
						
						Vertex va2 = va;
						Vertex vb2 = expHeights[line][pt];
						Vertex vc2 = vb;
						out.print(makeFacet(va, vb, vc));
						out.print(makeFacet(va2,vb2,vc2));
					}
				}else if(line == expHeights.length -1){	//back
					for(int pt = 0; pt < nLenX-1; pt++){
						Vertex va = expHeights[line][pt+1];
						Vertex vb = (Vertex) va.clone();
						vb.setZ(0.0);
						Vertex vc = (Vertex) expHeights[line][pt].clone();
						vc.setZ(0.0);
						
						Vertex va2 = va;
						Vertex vb2 = vc;
						Vertex vc2 = expHeights[line][pt];
						out.print(makeFacet(va, vb, vc));
						out.print(makeFacet(va2,vb2,vc2));
					}
				}if(line % 2 == 0 && line != expHeights.length-1){	//left & right sides
					//Left side
					Vertex va = expHeights[line+2][0];
					Vertex vb = (Vertex) va.clone();
					vb.setZ(0.0);
					Vertex vc = (Vertex) expHeights[line][0].clone();
					vc.setZ(0.0);
					
					Vertex va2 = va;
					Vertex vb2 = vc;
					Vertex vc2 = vc;
					
					//Right side
					Vertex va3 = expHeights[line+2][nLenX-1];
					Vertex vb3 = (Vertex) expHeights[line][nLenX-1].clone();
					vb3.setZ(0.0);
					Vertex vc3 = (Vertex) va3.clone();
					vc3.setZ(0.0);
					
					Vertex va4 = va3;
					Vertex vb4 = expHeights[line][nLenX-1];
					Vertex vc4 = (Vertex) vb4.clone();
					vc4.setZ(0.0);
					
					out.print(makeFacet(va, vb, vc));
					out.print(makeFacet(va2,vb2,vc2));
					out.print(makeFacet(va3,vb3,vc3));
					out.print(makeFacet(va4,vb4,vc4));
				}
			}
			
			//SURFACE CONTOURS
			for(int line = 0; line<expHeights.length-2; line++){
				
				if(line % 2 == 0){	//line with real data
					for(int pt = 0; pt < nLenX-1; pt++){
						Vertex vSW   = expHeights[line][pt];
						Vertex vNW   = expHeights[line+2][pt];
						Vertex vNE   = expHeights[line+2][pt+1];
						Vertex vSE   = expHeights[line][pt+1];
						Vertex vCent = expHeights[line+1][pt];
						
						out.print(makeFacet(vSW, vCent, vNW));	//West facet
						out.print(makeFacet(vNW, vCent, vNE));	//North facet
						out.print(makeFacet(vNE, vCent, vSE));	//East facet
						out.print(makeFacet(vSE, vCent, vSW));	//South facet
					}
				}else{}
			}
			out.print("endsolid "+ sModelName + "\n");
			
			
		}catch (IOException e){
			IJ.error("Error writing file. :(");
			return false;
		}finally{
			out.close();
		}
		return true;
	}




	/**
	 * Writes a binary file to the specified file path. Returns a boolean to indicate success or failure.
	 * 
	 * @param sPath a String specifying the file path
	 * @return boolean true indicates successful STL creation, false indicates one several errors was thrown
	 */
	public boolean saveAsBinary(String sPath){
		try{
			Path path 		= Path.resolve(sPath);							// resolve string to a "Path" that we can write to

			// how many bytes will this thing be
			// header = (UINT8 * 80) + UINT32       = 84 bytes   
			//
			// each facet = 12 * (REAL32) + UINT16  = 50 bytes
			// header + facets*N                    = 84 + 50*N
			//int headerLen	= 84;
			//int facetLen	= 50;
			int facets 		= (4*nLenX*nLenY + 2*(nLenX + nLenY) - 10);
			int length 		= BYTES_IN_HEADER + BYTES_PER_FACET * facets ;
			byte[] bytes 	= byte[length];

			char[] header 	= sDescription.substring(0,80).toCharArray();	// 80 character header stuff

			ByteBuffer main;												//
			
			for(int i =  0; i < header.length; i++){
				//bytes[i] = header[i];
				main.putChar(header[i]);									//
			}
			String sFacets = Integer.toBinaryString(facets);
			String s;
			for(int i = 0; i<sFacets.length(); i=i+8){
				s = sFacets.substring(i, i+8);
				main.put(Byte.valueOf(s, 2).byteValue());					//
				//b = Byte.valueOf(s, 2).byteValue();
				//bytes[] = b;
			}
			
			//BEGIN WRITING THE FACETS
			byte[] pixels = (byte[])ip_gray.getPixels();	//make a big 1D array
															//values ranging from -128 to 127
			short[] rawHeights = new short[pixels.length];	//big 1D array
			
			int nLowest = 255;
			
			//FIND LOWEST HEIGHT VALUE & MAKE COPY ARRAY
			for(int pix = 0; pix < pixels.length; pix++){
				rawHeights[pix] = (short)(pixels[pix] & 0xff);	//make values range from 0-255 shrtPix;
				if(rawHeights[pix] < nLowest){
					nLowest = rawHeights[pix];
				}
				
			}
			
			//MAKE THE ARRAY FOLLOW THE RIGHT-HAND-RULE
			Vertex vertices[][] = new Vertex[nLenY][nLenX];	//nLenY rows of nLenX points each
			int nLoc = rawHeights.length;
			double height;
			for(int row = 0; row < nLenY; row++){
				for(int pt = nLenX-1; pt >= 0; pt--){
					height = (( (rawHeights[--nLoc]) - nLowest ) * dZScaleFactor) + dBasethickness;
					Vertex v = new Vertex(pt*nmPerSample,row*nmPerSample,height);
					vertices[row][pt] = v;
				}
			}
			//MAKE EXPANDED ARRAY
			Vertex expHeights[][] = new Vertex[(nLenY*2)-1][];//nLenX];
			for(int row = 0; row < nLenY; row++){
				expHeights[row*2] = Arrays.copyOf(vertices[row], nLenX);
				Vertex[] newRow = new Vertex[nLenX-1];
				
				if(row < nLenY-1){
					for(int pt = 0; pt < nLenX-1; pt++){
						Vertex v 		= vertices[row][pt];
						Vertex nextV	= vertices[row][pt+1];
						Vertex v2		= vertices[row+1][pt];
						Vertex nextV2	= vertices[row+1][pt+1];
						
						double newX	= v.getX() + (nmPerSample/2);
						double newY	= v.getY() + (nmPerSample/2);
						double newZ	= (v.getZ()+nextV.getZ()+v2.getZ()+nextV2.getZ())/4;
						
						Vertex newV = new Vertex(newX, newY, newZ);
						newRow[pt] = newV;
					}	
					expHeights[(row*2)+1] = newRow;
				}
			}
			
			//MAKE THE FLOOR
			for(int line = 0; line<expHeights.length; line++){
				if(line == 0){
					for(int pt = 0; pt < nLenX-1; pt++){
						Vertex va = (Vertex) expHeights[line][pt].clone();
						Vertex vb = (Vertex) expHeights[line+2][nLenX-1].clone();
						Vertex vc = (Vertex) expHeights[line][pt+1].clone();
						va.setZ(0.0);
						vb.setZ(0.0);
						vc.setZ(0.0);
						
						main.put(makeBinaryFacet(va,vb,vc));
					}
				}else if(line == expHeights.length-1){
					for(int pt = 0; pt < nLenX-1; pt++){
						Vertex va = (Vertex) expHeights[line][pt].clone();
						Vertex vb = (Vertex) expHeights[line][pt+1].clone();
						Vertex vc = (Vertex) expHeights[line-2][0].clone();
						va.setZ(0.0);
						vb.setZ(0.0);
						vc.setZ(0.0);
						
						main.put(makeBinaryFacet(va,vb,vc));
					}
				}else if(line % 2 == 0){
					Vertex va = (Vertex) expHeights[line][0].clone();
					Vertex vb = (Vertex) expHeights[line][nLenX-1].clone();
					Vertex vc = (Vertex) expHeights[line-2][0].clone();
					va.setZ(0.0);
					vb.setZ(0.0);
					vc.setZ(0.0);
					main.put(makeBinaryFacet(va,vb,vc));
					
					Vertex va2 = (Vertex) expHeights[line][0].clone();
					Vertex vb2 = (Vertex) expHeights[line+2][nLenX-1].clone();	//NullPointerException!!!
					Vertex vc2 = (Vertex) expHeights[line][nLenX-1].clone();
					va2.setZ(0.0);
					vb2.setZ(0.0);
					vc2.setZ(0.0);
					main.put(makeBinaryFacet(va2,vb2,vc2));
				}else{}
			}
			
			//MAKE EDGES / SIDES
			for(int line = 0; line<expHeights.length; line++){
				if(line == 0){	//front
					for(int pt = 0; pt < nLenX-1; pt++){
						Vertex va = expHeights[line][pt+1];
						Vertex vb = (Vertex) expHeights[line][pt].clone();
						vb.setZ(0.0);
						Vertex vc = (Vertex) va.clone();
						vc.setZ(0.0);
						
						Vertex va2 = va;
						Vertex vb2 = expHeights[line][pt];
						Vertex vc2 = vb;
						main.put(makeBinaryFacet(va, vb, vc));
						main.put(makeBinaryFacet(va2,vb2,vc2));
					}
				}else if(line == expHeights.length -1){	//back
					for(int pt = 0; pt < nLenX-1; pt++){
						Vertex va = expHeights[line][pt+1];
						Vertex vb = (Vertex) va.clone();
						vb.setZ(0.0);
						Vertex vc = (Vertex) expHeights[line][pt].clone();
						vc.setZ(0.0);
						
						Vertex va2 = va;
						Vertex vb2 = vc;
						Vertex vc2 = expHeights[line][pt];
						main.put(makeBinaryFacet(va, vb, vc));
						main.put(makeBinaryFacet(va2,vb2,vc2));
					}
				}if(line % 2 == 0 && line != expHeights.length-1){	//left & right sides
					//Left side
					Vertex va = expHeights[line+2][0];
					Vertex vb = (Vertex) va.clone();
					vb.setZ(0.0);
					Vertex vc = (Vertex) expHeights[line][0].clone();
					vc.setZ(0.0);
					
					Vertex va2 = va;
					Vertex vb2 = vc;
					Vertex vc2 = vc;
					
					//Right side
					Vertex va3 = expHeights[line+2][nLenX-1];
					Vertex vb3 = (Vertex) expHeights[line][nLenX-1].clone();
					vb3.setZ(0.0);
					Vertex vc3 = (Vertex) va3.clone();
					vc3.setZ(0.0);
					
					Vertex va4 = va3;
					Vertex vb4 = expHeights[line][nLenX-1];
					Vertex vc4 = (Vertex) vb4.clone();
					vc4.setZ(0.0);
					
					main.put(makeBinaryFacet(va, vb, vc));
					main.put(makeBinaryFacet(va2,vb2,vc2));
					main.put(makeBinaryFacet(va3,vb3,vc3));
					main.put(makeBinaryFacet(va4,vb4,vc4));
				}
			}
			
			//SURFACE CONTOURS
			for(int line = 0; line<expHeights.length-2; line++){
				
				if(line % 2 == 0){	//line with real data
					for(int pt = 0; pt < nLenX-1; pt++){
						Vertex vSW   = expHeights[line][pt];
						Vertex vNW   = expHeights[line+2][pt];
						Vertex vNE   = expHeights[line+2][pt+1];
						Vertex vSE   = expHeights[line][pt+1];
						Vertex vCent = expHeights[line+1][pt];
						
						main.put(makeBinaryFacet(vSW, vCent, vNW));	//West facet
						main.put(makeBinaryFacet(vNW, vCent, vNE));	//North facet
						main.put(makeBinaryFacet(vNE, vCent, vSE));	//East facet
						main.put(makeBinaryFacet(vSE, vCent, vSW));	//South facet
					}
				}else{}
			}
			//main.put("endsolid "+ sModelName + "\n");

			main.order(ByteOrder.LITTLE_ENDIAN);

			path = Files.write(path, main.array(), StandardOpenOption.APPEND);
		}catch(IOException e){
			return false;
		}catch(UnsupportedOperationException e){
			return false;
		}catch(SecurityException e){
			return false;
		}
		finally{

		}
		return false;
	}
	
	/**
	 * Method to construct a binary formatted definition of a facet based on three vertices.
	 * 
	 * @param va first Vertex object
	 * @param vb next Vertex object, ordered following the right-hand-rule
	 * @param vc final Vertex object, following right-hand-rule
	 *
	 * @return byte[] of length 50 which defines the facet specified by the 3 vertices.
	 */
	public byte[] makeBinaryFacet(Vertex va, Vertex vb, Vertex vc){
		byte[] bytes 		= byte[BYTES_PER_FACET];//array to eventually return
		
		//Floats are 32-bit
		float[] n 			= float[3];				//will deal with later
		float[] v1 			= {(float)va.getX(), (float)va.getY(), (float)va.getZ()};
		float[] v2 			= {(float)vb.getX(), (float)vb.getY(), (float)vb.getZ()};
		float[] v3 			= {(float)vc.getX(), (float)vc.getY(), (float)vc.getZ()};

		float[][] vertices 	= {v1, v2, v3};			//


		//create a ByteBuffer
		ByteBuffer bb;								//

		//create an array of bytes by PUTTING values into it
		for(int i = 0; i<3; i++){
			for(int j=0; j<3; j++){
				bb.putFloat(vertices[i][j]);		// add onto our ByteBuffer
			}
		}

		bb = bb.order(ByteOrder.LITTLE_ENDIAN);		//order the bytes as Little endian

		nFacets++;									//keeping track of # of facets created

		bytes = bb.array();							//dump buffer into our fixed size array
		return bytes;
	}

	/**
	 * Method to construct a string that describes a facet, described by 3
	 * vertices, in STL format. The ouput of this method will be written 
	 * to the output file.
	 * 
	 * @param va first Vertex object
	 * @param vb next Vertex object, ordered following the right-hand-rule
	 * @param vc final Vertex object, following right-hand-rule
	 * @return the STL format string that will be written to the output file
	 */
	public String makeFacet(Vertex va, Vertex vb, Vertex vc){
		double[] v1 = {va.getX(), va.getY(), va.getZ()};
		double[] v2 = {vb.getX(), vb.getY(), vb.getZ()};
		double[] v3 = {vc.getX(), vc.getY(), vc.getZ()};
		
		String sOut = " facet normal 0 0 0\n"
				+ " outer loop \n"
				+ "  vertex "+v1[0]+" "+v1[1]+" "+v1[2]+"\n"
				+ "  vertex "+v2[0]+" "+v2[1]+" "+v2[2]+"\n"
				+ "  vertex "+v3[0]+" "+v3[1]+" "+v3[2]+"\n"
				+ " endloop\n"
				+ " endfacet\n";
		nFacets++;
		return sOut;
	}
	
	/** Ask the user for the parameters
     */
	public boolean showDialog(){

		GenericDialog gd = new GenericDialog(sTitle);
		sModelName = imp.getTitle().substring(0, (imp.getTitle().length())-4);
		gd.addStringField("Model Name", sModelName, 20);
		gd.addNumericField("Scan Size (before resampling)",2500.0, 0, 10, "nm");
		gd.addNumericField("Samples/Line (before resampling)",1024.0, 0, 10, "");
		gd.addNumericField("Height of a saturated pixel on model",5.0, 0, 10, "mm");
		gd.addNumericField("Base Thickness",1.0, 0, 10, "mm");
		gd.addCheckbox("Binary STL",false);
		gd.showDialog();
		
		if(gd.wasCanceled()){return false;}
		
		sModelName 			= gd.getNextString();
		dScanSizeNM 		= gd.getNextNumber();
		dSamplesPerLine		= gd.getNextNumber();
		double dHiPtModelmm	= gd.getNextNumber();
		dBasethickness		= gd.getNextNumber();
		nmPerSample			= dScanSizeNM/dSamplesPerLine;
		isBinary			= gd.getNextBoolean();
		
		dZScaleFactor = dHiPtModelmm/255;
		
		return true;
	}
	
	void showAbout(){
		IJ.showMessage(sTitle, sDescription);
	}

}