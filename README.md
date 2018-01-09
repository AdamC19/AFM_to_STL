# AFM_to_STL convert

## Overview:
This is a plugin, written in Java for use with [NIH ImageJ](https://imagej.nih.gov/ij/ "ImageJ Homepage"). The plugin is a filter that takes an image, copies it and converts it to grayscale, then uses the data to produce an STL format file suitable for 3D printing. It creates a surface using these, treating brighter value pixels as higher points. Before doing that, it creates a "floor" and "walls" (both required to facilitate 3D printability). The program can output the STL file in either ASCII or binary format. The ASCII format results in a rather large file, compared with the binary STL file containing the same information.

## Details on How the Program Works:
### Information Preservation
The program attempts to preserve as much information as is reasonable. To the best of my knowledge, it loses no information from the grayscale image copy to the STL. There is some loss going from the original image to grayscale, because the plugin uses only 8-bit grayscale.

## Installing the Plug-in
1. download the files
2. Locate the ImageJ "plugins" folder, which is in the top-level folder of your ImageJ installation. Create a folder called "AFM to STL". (The name has to match the title of the ".java" file with the underscores replaced with spaces.) 
3. Copy or move the ".java" file into this new folder. 
4. Open ImageJ, open an image that you would like to use the plugin on. Click "Plugins > Compile and Run...", then find the ".java" file you copied. 
5. Select the file and click "Open" -- the program should compile. 

## Usage


## Links
* [Documentation for the ImageJ API](https://imagej.nih.gov/ij/developer/api/ "API Documentation")
* [Tutorial on writing plugins for ImageJ](https://imagej.nih.gov/ij/download/docs/tutorial171.pdf "ImageJ Plugin Tutorial")
