package com.ichi2.anki;

import com.ichi2.anki.TemporaryModel;

/**
 * A class for holding changes made to the card types of a model. type is the type of change that occurred and ordinals are an array of the affected templates (ADD, REMOVE, and RENAME will only use the first element while REPOSITION will use the first element for the old position and the second element for the new position)
 */
public class TemplateChange {
    public enum oneAffectedOrd{ADD, DELETE};
    //public enum twoAffectedOrds{REPOSITION};
    //
    TemporaryModel.ChangeType type;
    int [] ordinalsAffected;

    public TemplateChange(TemporaryModel.ChangeType type, int [] ordinals){
        this.type = type;
        numAffectedOrds(type);
        this.ordinalsAffected = ordinals;
    }

    // Allow creation with an int and put it in an array
    public TemplateChange(TemporaryModel.ChangeType type, int ordinal){
        this.type = type;
        numAffectedOrds(type);
        this.ordinalsAffected[0] = ordinal;
    }

    // Repositions require tracking the old ord and the new ord. Adds, deletes, and renames only require tracking one ord.
    private int [] numAffectedOrds (TemporaryModel.ChangeType m){
        if (m == TemporaryModel.ChangeType.REPOSITION) {
            return new int[2];
        } else {
            return new int[1];
        }
    }

    public TemporaryModel.ChangeType getType() {
        return type;
    }

    public int [] getOrds() {
        return ordinalsAffected;
    }
}
