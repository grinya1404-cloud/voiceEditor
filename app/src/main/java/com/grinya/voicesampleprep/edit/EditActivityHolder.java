package com.grinya.voicesampleprep.edit;

import com.grinya.voicesampleprep.model.AudioClip;

public class EditActivityHolder {
    public static AudioClip current;

    public static void clear() {
        current = null;
    }
}

