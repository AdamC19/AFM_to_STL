# AFM_to_STL convert
======

## Overview:
This is a plugin, written in Java for use with [ImageJ](https://imagej.nih.gov/ij/ "ImageJ Homepage"). The plugin is a filter that takes an image, copies it and converts it to grayscale, then uses the data to produce an STL format file suitable for 3D printing. It creates a surface using these, treating brighter value pixels as higher points. Before doing that, it creates a "floor" and "walls" (both required to facilitate 3D printability). The program outputs the STL in ASCII format, resulting in a rather large file, compared with a binary STL file containing the same information.

======
## Details on How the Program Works:
### Information Preservation
The program attempts to preserve as much information as is reasonable. To the best of my knowledge, it loses no information from the grayscale image copy to the STL. There is some loss going from the original image to grayscale, because the plugin uses only 8-bit grayscale.
