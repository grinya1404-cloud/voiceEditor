package com.grinya.voicesampleprep.model;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Bank {
    public String name;
    public File dir;
    public List<AudioClip> clips = new ArrayList<>();
}