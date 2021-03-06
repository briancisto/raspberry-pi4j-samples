#include <stdio.h>
#include <strings.h>

#define NULL 0
#define TRUE 1
#define FALSE 0

typedef struct SMALL {
  struct SMALL * Next;
} StrPt;

void chainAppend (Head, Pt)
  StrPt * Head;
  StrPt * Pt;
{
  StrPt * StructPt = Head;

  while (StructPt->Next)
    StructPt = StructPt->Next;

  StructPt->Next = Pt;
}

void freeChain (Head)
  StrPt * Head;
{
  StrPt * NextOne;
  StrPt * ThisOne;

  ThisOne = Head;
  while (ThisOne) { 
    NextOne = ThisOne->Next;
    free((char *)ThisOne);
    ThisOne = NextOne;
  }
}
#define walkList(a,b) for (b=a;b;b=b->Next)

void chainRemove (Head, Pt)
  StrPt * Head;
  StrPt * Pt;
{
  StrPt * LocPt;

  walkList(Head, LocPt) {
    if (LocPt->Next == Pt) {
      LocPt->Next = Pt->Next;
      break;
    }
  }
}

void main () {
  typedef struct LOC {
    struct LOC * Next; // Match the SMALL one (StrPt)
    int a;
    char str[20];
  } LocStruct;
  
  LocStruct * StA;
  LocStruct * StB;
  LocStruct * StC;
  LocStruct * StD;

  LocStruct * MyStr;

  StA = (LocStruct *) calloc(1, sizeof(LocStruct));
  StB = (LocStruct *) calloc(1, sizeof(LocStruct));
  StC = (LocStruct *) calloc(1, sizeof(LocStruct));
  StD = (LocStruct *) calloc(1, sizeof(LocStruct));

  StA->Next = NULL;
  StB->Next = NULL;
  StC->Next = NULL;
  StD->Next = NULL;

  StA->a = 1;
  StB->a = 2;
  StC->a = 3;
  StD->a = 4;

  strcpy(StA->str, "First");
  strcpy(StB->str, "Second");
  strcpy(StC->str, "Third");
  strcpy(StD->str, "Fourth");

  chainAppend((StrPt *)StA, (StrPt *)StB);
  chainAppend((StrPt *)StA, (StrPt *)StC);
  chainAppend((StrPt *)StA, (StrPt *)StD);

  walkList(StA, MyStr) {
    fprintf(stdout, "a = %d\t", MyStr->a);
    fprintf(stdout, "str = %s\n", MyStr->str);
  }

  chainRemove((StrPt *)StA, (StrPt *)StC);
  free((char*)StC);
  fprintf (stdout, "After remove\n");
  walkList(StA, MyStr) {
    fprintf(stdout, "a = %d\t", MyStr->a);
    fprintf(stdout, "str = %s\n", MyStr->str);
  }

  freeChain (StA);
  fprintf (stdout, "Space is now free !\n");
}
