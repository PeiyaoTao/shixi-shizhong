package com.foldstandby.clock;
public class EditHistoryTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        EditHistory h=new EditHistory();
        h.record("A","B");h.record("B","C");
        check("B".equals(h.undo()),"undo last discrete edit");
        check("A".equals(h.undo()),"multiple undo steps");
        check("B".equals(h.redo()),"redo first edit");
        h.begin();h.record("B","drag1");h.record("drag1","drag2");h.end();
        check(!h.canRedo(),"new edit clears redo");
        check("B".equals(h.undo()),"entire drag undoes in one step");
        check("drag2".equals(h.redo()),"entire drag redoes in one step");
        h.begin();h.record("drag2","temp");h.record("temp","drag2");h.end();
        check("B".equals(h.undo()),"returning to initial value does not add history");
        EditHistory restored=new EditHistory();restored.restore(h.undoStates(),h.redoStates());
        check("drag2".equals(restored.redo()),"rotation preserves redo");
        check("B".equals(restored.undo()),"rotation preserves undo");
        h=new EditHistory();h.begin();h.record("A","B");check(h.canUndo(),"in-progress gesture can undo");check("A".equals(h.undo()),"undo finishes open gesture");
        for(int i=0;i<200;i++)h.record("before"+i,"after"+i);
        check(h.undoStates().size()<=80,"bounded history");
        System.out.println("PASS: edit transactions, branching, rotation and bounded history");
    }
}
