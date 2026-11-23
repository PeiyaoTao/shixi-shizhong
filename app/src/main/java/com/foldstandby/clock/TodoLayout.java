package com.foldstandby.clock;

/** Shared geometry for drawing, scrolling and tapping the task body. */
final class TodoLayout {
    private static final TodoLayout LIST=new TodoLayout(50,450,82,49,1);
    private static final TodoLayout CARDS=new TodoLayout(49,451,82,82,2);
    private static final TodoLayout PROGRESS=new TodoLayout(225,455,82,49,1);
    private static final TodoLayout BOARD=new TodoLayout(50,450,90,49,2);
    static final float BOTTOM=327;

    final float left,right,top,rowHeight;
    final int columns;

    private TodoLayout(float left,float right,float top,float rowHeight,int columns){
        this.left=left;this.right=right;this.top=top;this.rowHeight=rowHeight;this.columns=columns;
    }

    static TodoLayout of(Scene.Component component){
        switch(ComponentStyles.selected(component)){
            case "todo_cards":return CARDS;
            case "todo_progress":return PROGRESS;
            case "todo_board":return BOARD;
            default:return LIST;
        }
    }

    float viewport(){return BOTTOM-top;}
    float cellWidth(){return columns==1?right-left:(right-left-14)/2;}
    float cellLeft(int column){return left+column*(cellWidth()+14);}
    int rows(int count,int pending){
        if(this==BOARD)return Math.max(pending,count-pending);
        if(this==CARDS)return cardRows(pending)+cardRows(count-pending);
        return (count+columns-1)/columns;
    }
    float maxOffset(int count,int pending){return Math.max(0,rows(count,pending)*rowHeight-viewport());}

    int managedLeadingItems(int today,int completed){return this==CARDS?today-completed:today;}

    private int cardRows(int count){return (count+columns-1)/columns;}

    int index(int row,int column,int count,int pending){
        if(this==BOARD){
            int start=column==0?0:pending,end=column==0?pending:count;
            return start+row<end?start+row:-1;
        }
        if(this==CARDS){
            int pendingRows=cardRows(pending);
            int index=row<pendingRows?row*columns+column:pending+(row-pendingRows)*columns+column;
            return index<(row<pendingRows?pending:count)?index:-1;
        }
        int index=row*columns+column;
        return index<count?index:-1;
    }

    boolean contains(float x,float y){return x>=left&&x<=right&&y>=top&&y<BOTTOM;}

    int hit(float x,float y,float offset,int count,int pending){
        if(!contains(x,y))return -1;
        int column=columns==2&&x>=cellLeft(1)?1:0;
        if(x>cellLeft(column)+cellWidth())return -1;
        int row=(int)((y-top+offset)/rowHeight);
        return index(row,column,count,pending);
    }
}
